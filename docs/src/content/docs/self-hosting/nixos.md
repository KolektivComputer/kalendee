---
title: NixOS
description: >-
  Deploy Kalendee declaratively with the services.kalendee flake module, running
  the published OCI image against an externally managed PostgreSQL.
---

## How the module works

The flake at the repository root exports `nixosModules.default` (aliased as
`nixosModules.kalendee`). The module runs the published container image through
`virtualisation.oci-containers`, defaults to the **Podman** backend, mounts a
named volume at `/data`, and requires a PostgreSQL database that you create and
manage yourself.

Secrets never go in the Nix store. The module takes a `configFile` for
non-secret HOCON and an `environmentFile` for runtime secrets.

## Consuming the flake

```nix
{
  inputs.kalendee.url = "github:kolektivdev/kalendee";

  # In your NixOS configuration:
  modules = [ inputs.kalendee.nixosModules.default ];

  services.kalendee = {
    enable = true;
    publicUrl = "https://calendar.example.com";
    configFile = "/etc/kalendee/application.conf";
    environmentFile = config.sops.secrets."kalendee-env".path;
  };
}
```

`configFile` is a `path`, so its contents are copied into the world-readable,
immutable Nix store. Put only non-secret settings there — start from
[`application.conf.example`](https://github.com/kolektivdev/kalendee/blob/main/application.conf.example)
and let it reference secrets via `${?VAR}`. Secrets belong in `environmentFile`
(sops-nix, agenix, or any root-readable file).

## Options

| Option | Type | Default | Meaning |
| --- | --- | --- | --- |
| `enable` | bool | `false` | Turn on the Kalendee service. |
| `image` | str | `docker.yuri.capital/kolektiv/kalendee` | Container image to run. |
| `imageTag` | str | `latest` | Image tag. Pin a released version for reproducibility. |
| `port` | port | `8080` | Host port published to container port 8080. |
| `publicUrl` | null or str | `null` | Exported as `KALENDEE_PUBLIC_URL`. |
| `environment` | attrs of str | `{}` | Extra non-secret environment variables. |
| `environmentFile` | null or path | `null` | Runtime file with secrets; read by the container, never copied to the store. |
| `configFile` | null or path | `null` | HOCON file mounted read-only at `/config/application.conf` and selected via `KALENDEE_CONFIG`. |
| `volumes` | list of str | `[]` | Extra `host:container` mounts. A named volume `kalendee-data` is always mounted at `/data`. |
| `extraOptions` | list of str | `[]` | Extra Podman/Docker flags, e.g. `--network` or `--add-host`. |
| `openFirewall` | bool | `false` | Open `port` in the NixOS firewall. |

The module sets `KALENDEE_AVATAR_DIR=/data/avatars`, and when `configFile` is
set it sets `KALENDEE_CONFIG=/config/application.conf` and mounts the file
read-only.

## PostgreSQL is external

The module does **not** run a database. Create one separately and point the
server at it. The simplest setup is `services.postgresql` on the same host:

```nix
services.postgresql = {
  enable = true;
  package = pkgs.postgresql_17;
  ensureDatabases = [ "kalendee" ];
  ensureUsers = [{
    name = "kalendee";
    ensureDBOwnership = true;
  }];
};

services.kalendee = {
  enable = true;
  publicUrl = "https://calendar.example.com";
  environment = {
    KALENDEE_DATABASE_URL = "jdbc:postgresql://127.0.0.1:5432/kalendee";
    KALENDEE_DATABASE_USER = "kalendee";
  };
  environmentFile = config.sops.secrets."kalendee-env".path;
};
```

The database password goes in `environmentFile` as
`KALENDEE_DATABASE_PASSWORD`. If PostgreSQL runs in another container or host,
adjust `KALENDEE_DATABASE_URL` and container networking accordingly (the module
defaults to the Podman backend, so container-to-host access may need an
`extraOptions = [ "--network=host" ]` or an explicit network).

Because `services.postgresql` uses peer/ident authentication by default, a
password is still required for the TCP JDBC connection. Configure `pg_hba.conf`
to allow the `kalendee` role with `scram-sha-256` (or use `--network=host` and
adjust accordingly).

## Secrets and the admin seed

| Variable | Put in | Purpose |
| --- | --- | --- |
| `KALENDEE_DATABASE_PASSWORD` | `environmentFile` | Database password. |
| `KALENDEE_ADMIN_PASSWORD` | `environmentFile` | Seeds/promotes the admin on startup. |
| `KALENDEE_SECRET_KEY` | `environmentFile` | Token vault key for external calendars. |
| SMTP / OAuth / S3 credentials | `environmentFile` | Only for enabled integrations. |
| `KALENDEE_PUBLIC_URL` | `publicUrl` option (or `environment`) | Public base URL. |
| `KALENDEE_AVATAR_DIR` | set by the module | `/data/avatars`. |

On startup the server seeds the admin user from
`KALENDEE_ADMIN_USERNAME` / `KALENDEE_ADMIN_PASSWORD`, or promotes an existing
user of that name. See [Administration](/docs/self-hosting/administration).

## Plain HTTP deployments

`auth.cookieSecure` defaults to `true` outside development. If you terminate
TLS in front of the container with a separate proxy, keep it `true`. If you
serve plain HTTP (for example inside a trusted network), set it explicitly:

```nix
services.kalendee.environment.KALENDEE_COOKIE_SECURE = "false";
```

The same applies to `publicUrl`: it must be reachable exactly as clients use it.
See [Reverse proxy](/docs/self-hosting/reverse-proxy).

## Container backend

Podman is the default. To use Docker instead:

```nix
virtualisation.oci-containers.backend = "docker";
```

## Persistent data and avatars

The module always mounts the named volume `kalendee-data` at `/data`. To keep
avatars on a host directory instead of the volume, add a mount (as in the
module's own example):

```nix
services.kalendee.volumes = [ "/var/lib/kalendee/avatars:/data/avatars" ];
```

That directory must exist and be writable by the container's `kalendee` user
(UID 10001). If you enable S3/R2 object storage, avatar data lives in the bucket
instead and `/data` only needs to exist.

## Apply and check

```bash
sudo nixos-rebuild switch
systemctl status podman-kalendee.service     # or docker-kalendee.service
curl -fsS http://localhost:8080/api/v1/health
```

Logs come from the container runtime:

```bash
journalctl -u podman-kalendee.service -f
```
