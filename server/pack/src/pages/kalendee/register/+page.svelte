<script lang="ts">
  import { Head, Link, page, router, useAction } from "@kolektiv/keel-svelte"
  import { fieldError } from "../../../lib/errors"
  import type {
    RegisterIn,
    RegisterOut,
    RegisterPage,
    ResendVerificationIn,
    ResendVerificationOut,
  } from "../../../lib/page-types"

  const ctx = page<RegisterPage>()
  const register = useAction<RegisterIn, RegisterOut>("kalendee.register", { reload: false })
  const resend = useAction<ResendVerificationIn, ResendVerificationOut>("kalendee.resendVerification", {
    reload: false,
  })

  const policy = $derived(ctx.data.emailVerificationPolicy)
  const sentEmail = $derived(register.data?.email ?? "")
  const awaitingVerification = $derived(
    register.data != null && register.data.viewer == null && register.data.verificationRequired,
  )

  let username = $state("")
  let password = $state("")
  let email = $state("")

  function onSubmit(event: SubmitEvent) {
    event.preventDefault()
    const trimmed = email.trim()
    void register
      .mutateAsync({ username, password, email: trimmed || null })
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
        <h1 class="card-title text-2xl">Create account</h1>
        {#if awaitingVerification}
          <h2 class="text-lg font-semibold">Check your email</h2>
          <p class="text-base-content/70">
            We sent a verification link{#if sentEmail}
              to <span class="font-medium">{sentEmail}</span>{/if}. Open it to activate your account, then
            <Link href="/login" class="link">sign in</Link>.
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
        {:else if ctx.data.registrationOpen}
          <p class="text-base-content/70">Usernames are 3–32 characters. Passwords need at least 8.</p>
          <form class="flex flex-col gap-4" onsubmit={onSubmit}>
            <fieldset class="fieldset min-w-0">
              <legend class="fieldset-legend">Username</legend>
              <input id="username" class="input w-full min-w-0" name="username" bind:value={username} autocomplete="username" required />
              {#if fieldError(register.error, "username")}
                <p class="label whitespace-normal text-error">{fieldError(register.error, "username")}</p>
              {/if}
            </fieldset>
            <fieldset class="fieldset min-w-0">
              <legend class="fieldset-legend">Password</legend>
              <input
                id="password"
                class="input w-full min-w-0"
                name="password"
                type="password"
                bind:value={password}
                autocomplete="new-password"
                required
              />
              {#if fieldError(register.error, "password")}
                <p class="label whitespace-normal text-error">{fieldError(register.error, "password")}</p>
              {/if}
            </fieldset>
            <fieldset class="fieldset min-w-0">
              <legend class="fieldset-legend flex-wrap">
                Email{#if policy !== "required"} <span class="text-base-content/50">(optional)</span>{/if}
              </legend>
              <input
                id="email"
                class="input w-full min-w-0"
                name="email"
                type="email"
                bind:value={email}
                autocomplete="email"
                required={policy === "required"}
              />
              {#if fieldError(register.error, "email")}
                <p class="label whitespace-normal text-error">{fieldError(register.error, "email")}</p>
              {/if}
              {#if policy === "required"}
                <p class="label whitespace-normal">Required on this server. We'll email you a verification link.</p>
              {:else}
                <p class="label whitespace-normal">Add an email to enable verification and notifications. You can change it later in Settings.</p>
              {/if}
            </fieldset>
            <button type="submit" class="btn btn-primary" disabled={register.isPending}>
              {#if register.isPending}<span class="loading loading-spinner"></span>{/if}
              {register.isPending ? "Creating…" : "Create account"}
            </button>
          </form>
        {:else}
          <p class="text-base-content/70">Registration is closed on this server. Sign in with the seeded admin account.</p>
        {/if}
        <p class="text-sm text-base-content/50">Already have an account? <Link href="/login" class="link">Sign in</Link>.</p>
      </div>
    </div>
  </div>
</div>
