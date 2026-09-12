import { ActionError } from "@kolektiv/keel-svelte"

export function fieldError(error: unknown, field: string): string | undefined {
  if (error instanceof ActionError) return error.errors[field]?.join(" ")
  return undefined
}

export function actionMessage(error: unknown): string {
  if (!error) return ""
  if (error instanceof ActionError) {
    const messages = Object.values(error.errors).flat()
    if (messages.length > 0) return messages.join(" ")
    return error.message
  }
  if (error instanceof Error && error.message) return error.message
  return "Something went wrong. Try again."
}
