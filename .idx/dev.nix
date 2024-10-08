# To learn more about how to use Nix to configure your environment
# see: https://developers.google.com/idx/guides/customize-idx-env
{ pkgs, ... }: {
  # Which nixpkgs channel to use.
  channel = "stable-23.11"; # or "unstable"

  # Use https://search.nixos.org/packages to find packages
  packages = [
    pkgs.maven
    # pkgs.python311
    # pkgs.python311Packages.pip
    # pkgs.nodejs_20
    # pkgs.nodePackages.nodemon
  ];

  # Sets environment variables in the workspace
  env = {
    PORT = "37633";
  };
  idx = {
    # Search for the extensions you want on https://open-vsx.org/ and use "publisher.id"
    extensions = [
      "jellydn.toggle-excluded-files"
      "waderyan.gitblame"
      "kie-group.dmn-vscode-extension"
      "rangav.vscode-thunder-client" # FIXME: need to find a different solution for testing; see https://github.com/prestoncabe/dmn-benefit-toolbox/issues/23
      "redhat.vscode-xml"
      "mhutchie.git-graph"
    ];

    # Enable previews
    previews = {
      enable = true;
      previews = {
        web = {
          command = [
            "npx"
            "live-server"
            "--port=$PORT"
            "--entry-file=./src/main/resources/forms/viewer.html"
          ];
          manager = "web";
        };
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
      };
    };

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
