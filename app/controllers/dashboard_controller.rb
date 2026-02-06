class DashboardController < ApplicationController
  before_action :require_account
  before_action :require_authentication
  before_action :require_manager
  before_action :require_admin, only: [:audit, :repair]

  def index
    report = ActivityReport.new(account: current_account, since: 1.week.ago)

    @tags_by_time = report.time_per_tag
    @cards_by_time = report.time_per_card.sort_by { |_, v| -v }.first(10)
    @user_totals = report.time_per_user.sort_by { |_, v| -v }
    @total_focused = report.total_focused_time
    @avg_focus_per_user = report.avg_focus_per_user
    @daily_focus = report.daily_focus
    @switches_per_day = report.switches_per_day
    @avg_segment = report.avg_segment_length
  end

  def audit
    auditor = SegmentAuditor.new(account: current_account)
    @audit = auditor.audit
  end

  def repair
    auditor = SegmentAuditor.new(account: current_account)
    repaired_orphaned = auditor.repair_orphaned!
    repaired_boundaries = auditor.repair_boundary_violations!

    redirect_to audit_dashboard_path(current_account.slug),
      notice: "Repaired #{repaired_orphaned} orphaned + #{repaired_boundaries} boundary violations"
  end
end
