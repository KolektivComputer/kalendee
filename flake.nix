# Kalendee as a NixOS module.
#
# Consume from another flake:
#
#   inputs.kalendee.url = "github:kolektiv/kalendee";
#   modules = [ inputs.kalendee.nixosModules.default ];
#   services.kalendee = {
#     enable = true;
#     publicUrl = "https://calendar.example.com";
#     environmentFile = config.sops.secrets."kalendee-env".path;
#   };
{
  description = "Kalendee — self-hosted CalDAV server and multiplatform clients";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { ... }:
    {
      nixosModules = {
        default = ./nix/kalendee.nix;
        kalendee = ./nix/kalendee.nix;
      };
    };
}
