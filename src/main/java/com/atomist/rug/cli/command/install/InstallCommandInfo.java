package com.atomist.rug.cli.command.install;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.atomist.rug.cli.command.AbstractLocalArtifactDescriptorProvider;
import com.atomist.rug.cli.command.CommandInfo;

public class InstallCommandInfo extends AbstractLocalArtifactDescriptorProvider
        implements CommandInfo {

    public InstallCommandInfo() {
        super(InstallCommand.class, "install");
    }

    @Override
    public String description() {
        return "Create and install an archive into the local repository";
    }

    @Override
    public String detail() {
        return "Create and install an archive from the current project in the local repository.  Ensure that there "
                + "is a manifest.yml descriptor in the .atomist directory.";
    }

    @Override
    public Options options() {
        Options options = new Options();
        options.addOption(Option.builder("a").longOpt("archive-version").argName("AV").hasArg(true)
                .required(false).desc("Override archive version with AV").build());
        return options;
    }
    
    @Override
    public int order() {
        return 60;
    }

    @Override
    public String usage() {
        return "install [OPTION]...";
    }
}
