require "test_helper"

class SegmentAuditorTest < ActiveSupport::TestCase
  setup do
    @account = Account.create!(name: "Audit Test", slug: "audit-test", working_hours: {
      "timezone" => "Asia/Jerusalem",
      "days" => [0, 1, 2, 3, 4],
      "start_hour" => 9,
      "end_hour" => 18
    })
    @user = @account.users.create!(name: "Auditor", email_address: "audit@test.com", password: "password")
    Membership.create!(account: @account, user: @user, role: "member")

    @card = @account.cards.create!(title: "Audit card", creator: @user)
    @auditor = SegmentAuditor.new(account: @account)
  end

  test "detects overlapping segments" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]

    @account.activity_segments.create!(card: @card, user: @user,
      started_at: tz.local(2026, 2, 4, 10, 0), stopped_at: tz.local(2026, 2, 4, 12, 0))
    @account.activity_segments.create!(card: @card, user: @user,
      started_at: tz.local(2026, 2, 4, 11, 0), stopped_at: tz.local(2026, 2, 4, 13, 0))

    issues = @auditor.overlapping_segments
    assert_equal 1, issues.size
    assert issues.first[:overlap_seconds] > 0
  end

  test "no overlaps when segments are sequential" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]

    @account.activity_segments.create!(card: @card, user: @user,
      started_at: tz.local(2026, 2, 4, 10, 0), stopped_at: tz.local(2026, 2, 4, 12, 0))
    @account.activity_segments.create!(card: @card, user: @user,
      started_at: tz.local(2026, 2, 4, 12, 0), stopped_at: tz.local(2026, 2, 4, 13, 0))

    assert_empty @auditor.overlapping_segments
  end

  test "detects boundary crossing segments" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]

    @account.activity_segments.create!(card: @card, user: @user,
      started_at: tz.local(2026, 2, 4, 16, 0), stopped_at: tz.local(2026, 2, 4, 20, 0))

    issues = @auditor.boundary_crossing_segments
    assert_equal 1, issues.size
  end

  test "detects long segments" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]

    @account.activity_segments.create!(card: @card, user: @user,
      started_at: tz.local(2026, 2, 4, 8, 0), stopped_at: tz.local(2026, 2, 4, 20, 0))

    issues = @auditor.long_segments(max_hours: 8)
    assert_equal 1, issues.size
    assert issues.first[:hours] > 8
  end

  test "detects orphaned open segments" do
    @account.activity_segments.create!(card: @card, user: @user,
      started_at: 1.hour.ago, stopped_at: nil)

    # Card is not active (no sys:active tag)
    assert_not @card.active?

    issues = @auditor.orphaned_open_segments
    assert_equal 1, issues.size
  end

  test "close_segment closes an open segment" do
    segment = @account.activity_segments.create!(card: @card, user: @user,
      started_at: 1.hour.ago, stopped_at: nil)

    @auditor.close_segment(segment)

    segment.reload
    assert_not_nil segment.stopped_at
  end

  test "truncate_segment sets stopped_at to given time" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    segment = @account.activity_segments.create!(card: @card, user: @user,
      started_at: tz.local(2026, 2, 4, 14, 0), stopped_at: tz.local(2026, 2, 4, 20, 0))

    @auditor.truncate_segment(segment, at: tz.local(2026, 2, 4, 18, 0))

    segment.reload
    assert_equal 18, segment.stopped_at.in_time_zone(tz).hour
  end

  test "split_segment creates two segments" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    segment = @account.activity_segments.create!(card: @card, user: @user,
      started_at: tz.local(2026, 2, 4, 10, 0), stopped_at: tz.local(2026, 2, 4, 16, 0))

    @auditor.split_segment(segment, at: tz.local(2026, 2, 4, 13, 0))

    segment.reload
    assert_equal 13, segment.stopped_at.in_time_zone(tz).hour

    second = @account.activity_segments.where.not(id: segment.id).last
    assert_equal 13, second.started_at.in_time_zone(tz).hour
    assert_equal 16, second.stopped_at.in_time_zone(tz).hour
  end

  test "repair_orphaned closes orphaned segments" do
    @account.activity_segments.create!(card: @card, user: @user,
      started_at: 1.hour.ago, stopped_at: nil)

    count = @auditor.repair_orphaned!
    assert_equal 1, count
    assert_empty @auditor.orphaned_open_segments
  end

  test "repair_boundary_violations truncates at boundary" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    @account.activity_segments.create!(card: @card, user: @user,
      started_at: tz.local(2026, 2, 4, 16, 0), stopped_at: tz.local(2026, 2, 4, 20, 0))

    count = @auditor.repair_boundary_violations!
    assert_equal 1, count
    assert_empty @auditor.boundary_crossing_segments
  end

  test "audit returns all issue categories" do
    result = @auditor.audit
    assert_kind_of Hash, result
    assert result.key?(:overlapping)
    assert result.key?(:boundary_violations)
    assert result.key?(:long_segments)
    assert result.key?(:orphaned_open)
  end
end
