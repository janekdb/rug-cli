package com.atomist.rug.cli.command;

import static scala.collection.JavaConversions.asJavaCollection;

import java.io.File;
import java.net.URI;
import java.util.Collection;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.springframework.util.StringUtils;

import com.atomist.event.SystemEvent;
import com.atomist.plan.TreeMaterializer;
import com.atomist.project.archive.Operations;
import com.atomist.rug.BadRugException;
import com.atomist.rug.RugRuntimeException;
import com.atomist.rug.cli.Log;
import com.atomist.rug.cli.command.utils.ArtifactSourceUtils;
import com.atomist.rug.cli.output.ProgressReportingOperationRunner;
import com.atomist.rug.cli.settings.SettingsReader;
import com.atomist.rug.cli.tree.ArtifactSourceTreeCreator;
import com.atomist.rug.cli.tree.LogVisitor;
import com.atomist.rug.cli.utils.ArtifactDescriptorUtils;
import com.atomist.rug.cli.utils.CommandLineOptions;
import com.atomist.rug.cli.utils.FileUtils;
import com.atomist.rug.compiler.Compiler;
import com.atomist.rug.compiler.ServiceLoaderCompilerRegistry;
import com.atomist.rug.compiler.typescript.TypeScriptCompiler;
import com.atomist.rug.compiler.typescript.compilation.CompilerFactory;
import com.atomist.rug.kind.service.ConsoleMessageBuilder;
import com.atomist.rug.loader.DecoratingOperationsLoader;
import com.atomist.rug.loader.HandlerOperationsLoader;
import com.atomist.rug.loader.Handlers;
import com.atomist.rug.loader.OperationsAndHandlers;
import com.atomist.rug.loader.OperationsLoaderException;
import com.atomist.rug.loader.OperationsLoaderRuntimeException;
import com.atomist.rug.resolver.ArtifactDescriptor;
import com.atomist.rug.resolver.ArtifactDescriptor.Extension;
import com.atomist.rug.resolver.LocalArtifactDescriptor;
import com.atomist.rug.resolver.UriBasedDependencyResolver;
import com.atomist.source.ArtifactSource;
import com.atomist.source.Deltas;
import com.atomist.tree.TreeNode;
import com.atomist.tree.pathexpression.PathExpression;

public abstract class AbstractCompilingAndOperationLoadingCommand extends AbstractCommand {

    protected Log log = new Log(getClass());

    @Override
    protected final void run(URI[] uri, ArtifactDescriptor artifact, CommandLine commandLine) {
        OperationsAndHandlers operationsAndHandlers = null;
        ArtifactSource source = null;
        if (artifact != null && artifact.extension() == Extension.ZIP
                && registry.findCommand(commandLine).loadArtifactSource()) {
            source = ArtifactSourceUtils.createArtifactSource(artifact);
            printArtifactSource(artifact, source);
            source = compile(artifact, source);
            OperationsAndHandlers operations = loadOperationsAndHandlers(artifact, source,
                    createOperationsLoader(uri));

            CommandEventListenerRegistry.raiseEvent((c) -> c.operationsLoaded(operations));

            operationsAndHandlers = operations;
        }
        run(operationsAndHandlers, artifact, source, commandLine);
    }

    private void printArtifactSource(ArtifactDescriptor artifact, ArtifactSource source) {
        if (CommandLineOptions.hasOption("V") && source != null) {
            log.info("Loaded archive sources for %s",
                    ArtifactDescriptorUtils.coordinates(artifact));
            log.info("  " + FileUtils.relativize(artifact.uri()));
            ArtifactSourceTreeCreator.visitTree(source, new LogVisitor(log));
        }
    }

    private ArtifactSource compile(ArtifactDescriptor artifact, ArtifactSource source) {
        // Only compile local archives
        if (artifact instanceof LocalArtifactDescriptor) {

            String root = new File(new File(artifact.uri()),
                    ".atomist" + File.separator + "target" + File.separator + ".jscache")
                            .getAbsolutePath();

            // Get all registered and supported compilers
            Collection<Compiler> compilers = asJavaCollection(
                    ServiceLoaderCompilerRegistry.findAll(source));

            if (!compilers.isEmpty()) {

                return new ProgressReportingOperationRunner<ArtifactSource>(
                        "Processing script sources").run(indicator -> {
                            ArtifactSource compiledSource = source;
                            for (Compiler compiler : compilers) {
                                compiler = wrapCompiler(compiler, root);
                                indicator.report(String.format("Invoking %s on %s script sources",
                                        compiler.name(),
                                        StringUtils.collectionToCommaDelimitedString(
                                                compiler.extensions())));
                                ArtifactSource cs = compiler.compile(compiledSource);
                                Deltas deltas = cs.deltaFrom(compiledSource);
                                if (deltas.empty()) {
                                    indicator.report("  No files modified");
                                }
                                else {
                                    asJavaCollection(deltas.deltas()).forEach(
                                            d -> indicator.report("  Created " + d.path()));
                                }
                                compiledSource = cs;
                            }
                            return compiledSource;
                        });
            }
        }
        return source;
    }

    private Compiler wrapCompiler(Compiler compiler, String root) {
        if (compiler instanceof TypeScriptCompiler) {
            return new TypeScriptCompiler(
                    CompilerFactory.cachingCompiler(CompilerFactory.create(), root));
        }
        else {
            return compiler;
        }
    }

    private HandlerOperationsLoader createOperationsLoader(URI[] uri) {
        HandlerOperationsLoader loader = new DecoratingOperationsLoader(
                new UriBasedDependencyResolver(uri,
                        new SettingsReader().read().getLocalRepository().path())) {
            @Override
            protected List<ArtifactDescriptor> postProcessArfifactDescriptors(
                    ArtifactDescriptor artifact, List<ArtifactDescriptor> dependencies) {
                if (artifact instanceof LocalArtifactDescriptor) {
                    dependencies.add(artifact);
                }
                return dependencies;
            }
        };
        return loader;
    }

    private OperationsAndHandlers doLoadOperationsAndHandlers(ArtifactDescriptor artifact,
            ArtifactSource source, HandlerOperationsLoader loader) throws Exception {
        try {
            Operations operations = loader.load(artifact, source);
            Handlers handlers = loader.loadHandlers("", artifact, source,
                    new ConsoleMessageBuilder("", null), new TreeMaterializer() {
                        @Override
                        public TreeNode rootNodeFor(SystemEvent systemEvent,
                                PathExpression pathExpression) {
                            return null;
                        }

                        @Override
                        public TreeNode hydrate(String teamId, TreeNode treeNode,
                                PathExpression pathExpression) {
                            return null;
                        }
                    });

            return new OperationsAndHandlers(operations, handlers);
        }
        catch (Exception e) {
            if (e instanceof BadRugException) {
                throw new CommandException("Failed to load archive: \n" + e.getMessage(), e);
            }
            else if (e instanceof OperationsLoaderException
                    || e instanceof OperationsLoaderRuntimeException) {
                if (e.getCause() instanceof BadRugException) {
                    throw new CommandException(
                            "Failed to load archive: \n" + e.getCause().getMessage(), e);
                }
                else if (e.getCause() instanceof RugRuntimeException) {
                    throw new CommandException(
                            "Failed to load archive: \n" + e.getCause().getMessage(), e);
                }
            }
            throw e;
        }
    }

    private OperationsAndHandlers loadOperationsAndHandlers(ArtifactDescriptor artifact,
            ArtifactSource source, HandlerOperationsLoader loader) {
        if (artifact == null || source == null) {
            return null;
        }

        return new ProgressReportingOperationRunner<OperationsAndHandlers>(String
                .format("Loading %s into runtime", ArtifactDescriptorUtils.coordinates(artifact)))
                        .run(indicator -> {
                            return doLoadOperationsAndHandlers(artifact, source, loader);
                        });
    }

    protected abstract void run(OperationsAndHandlers operationsAndHandlers,
            ArtifactDescriptor artifact, ArtifactSource source, CommandLine commandLine);

}
