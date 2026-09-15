<script lang="ts">
  import { Head, Link, page, router, useAction } from "@kolektiv/keel-svelte"
  import { fieldError } from "../../../lib/errors"
  import type {
    LoginIn,
    LoginOut,
    LoginPage,
    ResendVerificationIn,
    ResendVerificationOut,
  } from "../../../lib/page-types"

  page<LoginPage>()
  const login = useAction<LoginIn, LoginOut>("kalendee.login", { reload: false })
  const resend = useAction<ResendVerificationIn, ResendVerificationOut>("kalendee.resendVerification", {
    reload: false,
  })

  const awaitingVerification = $derived(
    login.data != null && login.data.viewer == null && login.data.verificationRequired,
  )

  let username = $state("")
  let password = $state("")

  function onSubmit(event: SubmitEvent) {
    event.preventDefault()
    void login
      .mutateAsync({ username, password })
      .then((result) => {
        if (result.viewer != null) void router.visit("/")
      })
      .catch(() => undefined)
  }

  function onResend() {
    const name = username.trim()
    if (!name) return
    void resend.mutateAsync({ username: name }).catch(() => undefined)
  }
</script>

<Head />

<div class="hero min-h-full">
  <div class="hero-content w-full">
    <div class="card bg-base-200 card-border w-full max-w-md">
      <div class="card-body">
        <h1 class="card-title text-2xl">Sign in</h1>
        {#if awaitingVerification}
          <h2 class="text-lg font-semibold">Verify your email</h2>
          <p class="text-base-content/70">
            This server requires a verified email before you can sign in.{#if login.data?.email}
              We sent a verification link to <span class="font-medium">{login.data.email}</span>.{/if}
          </p>
          <form
            class="flex flex-col gap-3"
            onsubmit={(event) => {
              event.preventDefault()
              onResend()
            }}
          >
            <button type="submit" class="btn btn-primary" disabled={resend.isPending}>
              {#if resend.isPending}<span class="loading loading-spinner"></span>{/if}
              {resend.isPending ? "Sending…" : "Resend verification email"}
            </button>
          </form>
          {#if resend.isSuccess}
            <p class="text-sm text-success">Verification email sent.</p>
          {/if}
          {#if resend.error}
            <p class="text-error text-sm">Could not send the email. Try again in a minute.</p>
          {/if}
        {:else}
          <p class="text-base-content/70">Use the admin account for this server, or another user if registration is open.</p>
          <form class="flex flex-col gap-4" onsubmit={onSubmit}>
            <fieldset class="fieldset">
              <legend class="fieldset-legend">Username</legend>
              <input id="username" class="input w-full" name="username" bind:value={username} autocomplete="username" required />
              {#if fieldError(login.error, "username")}
                <p class="label text-error">{fieldError(login.error, "username")}</p>
              {/if}
            </fieldset>
            <fieldset class="fieldset">
              <legend class="fieldset-legend">Password</legend>
              <input
                id="password"
                class="input w-full"
                name="password"
                type="password"
                bind:value={password}
                autocomplete="current-password"
                required
              />
              {#if fieldError(login.error, "password")}
                <p class="label text-error">{fieldError(login.error, "password")}</p>
              {/if}
            </fieldset>
            <button type="submit" class="btn btn-primary" disabled={login.isPending}>
              {#if login.isPending}<span class="loading loading-spinner"></span>{/if}
              {login.isPending ? "Signing in…" : "Sign in"}
            </button>
          </form>
        {/if}
        {#if ctx.data.oauthRegistration}
          {@const enabled = ctx.data.providers.filter((provider) => provider.enabled)}
          {#if enabled.length > 0}
            <div class="divider text-xs">or</div>
            <div class="flex flex-col gap-2">
              {#each enabled as provider (provider.id)}
                <a class="btn btn-neutral" href={provider.connectUrl}>Continue with {provider.displayName}</a>
              {/each}
            </div>
          {/if}
        {/if}
        <p class="text-sm text-base-content/50">Need an account? <Link href="/register" class="link">Create one</Link>.</p>
        <p class="text-xs text-base-content/40">
          <Link href="/privacy" class="link">Privacy</Link>
          ·
          <Link href="/terms" class="link">Terms</Link>
        </p>
      </div>
    </div>
  </div>
</div>
