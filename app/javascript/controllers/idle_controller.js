import { Controller } from "@hotwired/stimulus"

// Detects user going idle and prompts on return.
// Attach to body with data-controller="idle" on pages with an active card.
export default class extends Controller {
  static values = {
    threshold: { type: Number, default: 900 }, // 15 minutes in seconds
    deactivateUrl: String,
    adjustUrl: String
  }

  connect() {
    this.lastActivity = Date.now()
    this.wentIdleAt = null
    this.prompted = false

    this.trackActivity = this.trackActivity.bind(this)
    this.checkIdle = this.checkIdle.bind(this)
    this.handleVisibility = this.handleVisibility.bind(this)

    document.addEventListener("mousemove", this.trackActivity)
    document.addEventListener("keydown", this.trackActivity)
    document.addEventListener("click", this.trackActivity)
    document.addEventListener("visibilitychange", this.handleVisibility)

    this.interval = setInterval(this.checkIdle, 30000) // check every 30s
  }

  disconnect() {
    document.removeEventListener("mousemove", this.trackActivity)
    document.removeEventListener("keydown", this.trackActivity)
    document.removeEventListener("click", this.trackActivity)
    document.removeEventListener("visibilitychange", this.handleVisibility)
    clearInterval(this.interval)
  }

  trackActivity() {
    this.lastActivity = Date.now()
    if (this.wentIdleAt && !this.prompted) {
      this.prompted = true
      this.promptReturn()
    }
  }

  checkIdle() {
    const elapsed = (Date.now() - this.lastActivity) / 1000
    if (elapsed >= this.thresholdValue && !this.wentIdleAt) {
      this.wentIdleAt = new Date(this.lastActivity + this.thresholdValue * 1000).toISOString()
    }
  }

  handleVisibility() {
    if (document.visibilityState === "visible" && this.wentIdleAt && !this.prompted) {
      this.prompted = true
      this.promptReturn()
    }
  }

  promptReturn() {
    const idleMinutes = Math.round((Date.now() - new Date(this.wentIdleAt).getTime()) / 60000)

    // Show inline prompt instead of blocking dialog
    const banner = document.createElement("div")
    banner.className = "idle-prompt"
    banner.innerHTML = `
      <p>You were idle for ~${idleMinutes} minute${idleMinutes === 1 ? "" : "s"}. Still working on this card?</p>
      <div class="idle-actions">
        <button class="btn btn-primary" data-action="idle#keepActive">Yes, still working</button>
        <button class="btn" data-action="idle#stopWorking">No, deactivate</button>
      </div>
    `
    document.querySelector("main").prepend(banner)
  }

  keepActive() {
    this.reset()
    this.dismissPrompt()
  }

  stopWorking() {
    // POST to adjust endpoint to close segment at idle time, then deactivate
    if (this.hasAdjustUrlValue) {
      const csrfToken = document.querySelector("meta[name='csrf-token']")?.content

      fetch(this.adjustUrlValue, {
        method: "POST",
        headers: {
          "X-CSRF-Token": csrfToken,
          "Content-Type": "application/json",
          "Accept": "text/html"
        },
        body: JSON.stringify({ idle_since: this.wentIdleAt })
      }).then(() => {
        window.location.reload()
      })
    } else if (this.hasDeactivateUrlValue) {
      window.location.href = this.deactivateUrlValue
    }
  }

  reset() {
    this.lastActivity = Date.now()
    this.wentIdleAt = null
    this.prompted = false
  }

  dismissPrompt() {
    document.querySelector(".idle-prompt")?.remove()
  }
}
