# Kalendee NixOS module.
#
# Runs the published Kalendee container image and requires a reachable
# PostgreSQL database. Create one separately, for example with
# `services.postgresql` on the host or another container, and point
# `KALENDEE_DATABASE_URL` / `KALENDEE_DATABASE_USER` (and the password via
# `environmentFile`) at it.
#
# The default container backend is podman. Use Docker instead with:
#
#   virtualisation.oci-containers.backend = "docker";
#
# Secrets must never end up in the Nix store: put them in a runtime
# environment file (sops-nix, agenix, ...) and set `environmentFile`.
#
# On startup Kalendee seeds (or promotes) the admin user from
# `KALENDEE_ADMIN_USERNAME` / `KALENDEE_ADMIN_PASSWORD`.
#
# Example:
#
#   {
#     inputs.kalendee.url = "github:kolektiv/kalendee";
#     modules = [ inputs.kalendee.nixosModules.default ];
#
#     services.kalendee = {
#       enable = true;
#       publicUrl = "https://calendar.example.com";
#       environmentFile = config.sops.secrets."kalendee-env".path;
#     };
#   }
{
  config,
  lib,
  pkgs,
  ...
}:

let
  cfg = config.services.kalendee;
in
{
  options.services.kalendee = {
    enable = lib.mkEnableOption "Kalendee, a self-hosted CalDAV server (runs the published OCI image)";

    image = lib.mkOption {
      type = lib.types.str;
      default = "ghcr.io/kolektiv/kalendee";
      description = ''
        Container image to run. Change this if you mirror the image or use a
        different registry.
      '';
    };

    imageTag = lib.mkOption {
      type = lib.types.str;
      default = "latest";
      example = "1.2.3";
      description = ''
        Tag of the container image. Pin a released version instead of tracking
        `latest` so deployments stay reproducible.
      '';
    };

    port = lib.mkOption {
      type = lib.types.port;
      default = 8080;
      description = ''
        Host port published to port 8080 in the container.
      '';
    };

    publicUrl = lib.mkOption {
      type = lib.types.nullOr lib.types.str;
      default = null;
      example = "https://calendar.example.com";
      description = ''
        Public base URL of the instance. When set, exported to the container as
        `KALENDEE_PUBLIC_URL`.
      '';
    };

    environment = lib.mkOption {
      type = lib.types.attrsOf lib.types.str;
      default = { };
      description = ''
        Extra environment variables merged into the container environment.
        Useful for non-secret settings such as `KALENDEE_DATABASE_URL`,
        `KALENDEE_DATABASE_USER`, `KALENDEE_AUTH_REGISTRATION`, and similar.
      '';
    };

    environmentFile = lib.mkOption {
      type = lib.types.nullOr lib.types.path;
      default = null;
      description = ''
        Path to a runtime environment file with secrets such as
        `KALENDEE_DATABASE_PASSWORD` and `KALENDEE_ADMIN_PASSWORD`. Point this
        at a sops-nix or agenix managed file; never put secrets in the Nix
        store.
      '';
    };

    volumes = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
      example = [ "/var/lib/kalendee/avatars:/data/avatars" ];
      description = ''
        Extra volume mounts. A named volume `kalendee-data` is always mounted
        at `/data`.
      '';
    };

    extraOptions = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
      description = ''
        Extra `podman`/`docker` options, for example a `--network` or
        `--add-host` entry.
      '';
    };

    openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = ''
        Whether to open `services.kalendee.port` in the NixOS firewall.
      '';
    };
  };

  config = lib.mkIf cfg.enable {
    virtualisation.oci-containers.containers.kalendee = {
      image = "${cfg.image}:${cfg.imageTag}";
      ports = [ "${toString cfg.port}:8080" ];
      environment = {
        KALENDEE_AVATAR_DIR = "/data/avatars";
      }
      // lib.optionalAttrs (cfg.publicUrl != null) {
        KALENDEE_PUBLIC_URL = cfg.publicUrl;
      }
      // cfg.environment;
      environmentFiles = lib.optional (cfg.environmentFile != null) cfg.environmentFile;
      volumes = [ "kalendee-data:/data" ] ++ cfg.volumes;
      extraOptions = cfg.extraOptions;
    };

    networking.firewall.allowedTCPPorts = lib.mkIf cfg.openFirewall [ cfg.port ];
  };
}
