# To learn more about how to use Nix to configure your environment
# see: https://developers.google.com/idx/guides/customize-idx-env
{ pkgs, ... }: {
  # Which nixpkgs channel to use.
  channel = "stable-24.05"; # or "unstable"

  # Use https://search.nixos.org/packages to find packages
  packages = [
    pkgs.maven
    pkgs.quarkus
    pkgs.jdk17
  ];

  # Sets environment variables in the workspace
  env = {
    # PORT = "8080";
  };
  idx = {
    # Search for the extensions you want on https://open-vsx.org/ and use "publisher.id"
    extensions = [
      "jellydn.toggle-excluded-files"
      "waderyan.gitblame"
      "kie-group.dmn-vscode-extension"
      "rangav.vscode-thunder-client"
      "redhat.vscode-xml"
      "mhutchie.git-graph"
      "redhat.java"
      "redhat.vscode-quarkus"
    ];

    # Enable previews
    # previews = {
    #   enable = true;
    #   previews = {
    #     web = {
    #       command = [
    #         "bin/dev"
    #       ];
    #       manager = "web";
    #     };
        # web = {
        #   # Example: run "npm run dev" with PORT set to IDX's defined port for previews,
        #   # and show it in IDX's web preview panel
        #   command = ["npm" "run" "dev"];
        #   manager = "web";
        #   env = {
        #     # Environment variables to set for your server
        #     PORT = "$PORT";
        #   };
        # };
    #   };
    # };

    # Workspace lifecycle hooks
    workspace = {
      # Runs when a workspace is first created
      onCreate = {
        install-default-settings = "cp .idx/default-vscode-settings.jsonc /home/user/.codeoss-cloudworkstations/data/Machine/settings.json";
        install-thunder-client-tests = "ln -s /home/user/dmn-benefit-toolbox/thunder-tests/ /home/user/.codeoss-cloudworkstations/data/User/globalStorage/rangav.vscode-thunder-client";
      };
      # Runs when the workspace is (re)started
      onStart = {
        start-quarkus-dev-server = "bin/dev";
      };
    };
  };
}
