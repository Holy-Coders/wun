class WorkingHoursBoundaryJob < ApplicationJob
  queue_as :default

  def perform(account_id = nil)
    accounts = account_id ? Account.where(id: account_id) : Account.all

    accounts.find_each do |account|
      process_account(account)
    end
  end

  private

  def process_account(account)
    policy = WorkingHoursPolicy.new(account.effective_working_hours)
    boundary_time = policy.boundary_time_for

    # Find all open segments for this account
    account.activity_segments.open.includes(:user, :card).find_each do |segment|
      membership = account.memberships.find_by(user: segment.user)
      next unless membership

      # Skip if user has an active after-hours override
      next if membership.after_hours_override_active?

      # Use per-user policy if they have an override
      user_policy = if membership.working_hours_override.present?
        WorkingHoursPolicy.new(membership.working_hours_override)
      else
        policy
      end

      user_boundary = user_policy.boundary_time_for

      # Only auto-pause if we're past the boundary
      next if Time.current < user_boundary

      auto_pause(account, segment, user_boundary)
    end
  end

  def auto_pause(account, segment, boundary_time)
    # Close segment at the exact boundary time, not "now"
    close_at = [boundary_time, segment.started_at].max
    segment.update!(stopped_at: close_at)

    # Remove sys:active from the card
    engine = RuleEngine.new(account: account, actor: segment.user)
    engine.remove_tag(card: segment.card, tag_name: "sys:active")

    # Log the auto-pause event
    account.events.create!(
      card: segment.card,
      actor: segment.user,
      action: "working_hours_auto_pause",
      metadata: { boundary_time: boundary_time.iso8601 }
    )
  end
end
