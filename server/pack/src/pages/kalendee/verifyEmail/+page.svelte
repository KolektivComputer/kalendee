<script lang="ts">
  import { Head, Link, page, useAction } from "@kolektiv/keel-svelte"
  import type {
    ResendVerificationIn,
    ResendVerificationOut,
    VerifyEmailIn,
    VerifyEmailOut,
    VerifyEmailPage,
  } from "../../../lib/page-types"

  const ctx = page<VerifyEmailPage>()
  const verify = useAction<VerifyEmailIn, VerifyEmailOut>("kalendee.verifyEmail", { reload: false })
  const resend = useAction<ResendVerificationIn, ResendVerificationOut>("kalendee.resendVerification", {
    reload: false,
  })

  let username = $state("")

  function onVerify() {
    const token = ctx.data.token
    if (!token) return
    void verify.mutateAsync({ token }).catch(() => undefined)
  }

  function onResend() {
    const name = ctx.data.viewer?.username ?? username.trim()
    if (!name) return
    void resend.mutateAsync({ username: name }).catch(() => undefined)
  }
</script>

<Head />

{#snippet resendForm()}
  {#if !ctx.data.viewer}
    <fieldset class="fieldset">
      <legend class="fieldset-legend">Username</legend>
      <input
        id="resend-username"
        class="input w-full"
        name="username"
        bind:value={username}
        autocomplete="username"
        placeholder="Your username"
      />
    </fieldset>
  {/if}
  <button
    type="submit"
    class="btn btn-primary"
    disabled={resend.isPending || (!ctx.data.viewer && !username.trim())}
  >
    {#if resend.isPending}<span class="loading loading-spinner"></span>{/if}
    {resend.isPending ? "Sending…" : "Resend verification email"}
  </button>
{/snippet}

<div class="hero min-h-full">
  <div class="hero-content w-full">
    <div class="card bg-base-200 card-border w-full max-w-md">
      <div class="card-body">
        {#if ctx.data.token}
          {#if verify.data?.ok}
            <h1 class="card-title text-2xl">Email verified</h1>
            <p class="text-base-content/70">
              <span class="font-medium">{verify.data.email ?? "Your email address"}</span> is now verified. Notifications
              can reach you there.
            </p>
            <Link href={ctx.data.viewer ? "/" : "/login"} class="btn btn-primary">
              {ctx.data.viewer ? "Go to calendar" : "Sign in"}
            </Link>
          {:else if (verify.data && !verify.data.ok) || verify.error}
            <h1 class="card-title text-2xl">Verification failed</h1>
            <p class="text-error">This verification link is invalid or has expired.</p>
            <p class="text-base-content/70">Request a new link and open it from your inbox.</p>
            <form
              class="flex flex-col gap-3"
              onsubmit={(event) => {
                event.preventDefault()
                onResend()
              }}
            >
              {@render resendForm()}
            </form>
            {#if resend.isSuccess}
              <p class="text-sm text-success">If that account still needs verification, a new link is on its way.</p>
            {/if}
            {#if resend.error}
              <p class="text-error text-sm">Could not send the email. Try again in a minute.</p>
            {/if}
          {:else}
            <h1 class="card-title text-2xl">Verify your email</h1>
            <p class="text-base-content/70">
              Click the button below to confirm this email address and finish verification.
            </p>
            <button type="button" class="btn btn-primary" disabled={verify.isPending} onclick={onVerify}>
              {#if verify.isPending}<span class="loading loading-spinner"></span>{/if}
              {verify.isPending ? "Verifying…" : "Verify email address"}
            </button>
          {/if}
        {:else if ctx.data.viewer}
          <h1 class="card-title text-2xl">Check your inbox</h1>
          {#if ctx.data.viewer.email}
            <p class="text-base-content/70">
              We sent a verification link to <span class="font-medium">{ctx.data.viewer.email}</span>.
            </p>
            {#if ctx.data.viewer.emailVerified}
              <p class="text-success">Your email is already verified.</p>
              <Link href="/" class="btn btn-primary">Go to calendar</Link>
            {:else}
              <p class="text-base-content/70">Open it from your inbox, or request a new one.</p>
              <form
                class="flex flex-col gap-3"
                onsubmit={(event) => {
                  event.preventDefault()
                  onResend()
                }}
              >
                {@render resendForm()}
              </form>
              {#if resend.isSuccess}
                <p class="text-sm text-success">Verification email sent.</p>
              {/if}
              {#if resend.error}
                <p class="text-error text-sm">Could not send the email. Try again in a minute.</p>
              {/if}
            {/if}
          {:else}
            <p class="text-base-content/70">No email address is on file yet.</p>
            <Link href="/settings" class="btn btn-primary">Add an email in Settings</Link>
          {/if}
        {:else}
          <h1 class="card-title text-2xl">Verify your email</h1>
          <p class="text-base-content/70">
            Open the verification link from your email to confirm your address. The link is only valid for a limited
            time.
          </p>
          <p class="text-sm text-base-content/50">
            Already verified? <Link href="/login" class="link">Sign in</Link>.
          </p>
        {/if}
      </div>
    </div>
  </div>
</div>
