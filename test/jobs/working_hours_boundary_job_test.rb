require "test_helper"

class WorkingHoursBoundaryJobTest < ActiveJob::TestCase
  setup do
    @account = Account.create!(name: "Test", slug: "test-wh", working_hours: {
      "timezone" => "Asia/Jerusalem",
      "days" => [0, 1, 2, 3, 4],
      "start_hour" => 9,
      "end_hour" => 18
    })
    @user = @account.users.create!(name: "Worker", email_address: "worker@test.com", password: "password")
    @membership = Membership.create!(account: @account, user: @user, role: "member")

    engine = RuleEngine.new(account: @account, actor: @user)
    @card = engine.create_card(title: "Test card")
    engine.activate(card: @card)

    # Verify there's an open segment
    @segment = @account.activity_segments.open.for_user(@user).first
    assert @segment, "Expected an open segment after activation"
  end

  test "auto-pauses open segments when past boundary time" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    # Set segment started_at to during working hours
    @segment.update!(started_at: tz.local(2026, 2, 4, 14, 0))

    # Travel to after boundary (18:00 Jerusalem = past end_hour)
    travel_to tz.local(2026, 2, 4, 19, 0) do
      WorkingHoursBoundaryJob.perform_now(@account.id)
    end

    @segment.reload
    assert_not_nil @segment.stopped_at, "Segment should be closed"
    assert_equal 18, @segment.stopped_at.in_time_zone(tz).hour, "Segment should close at boundary time"
  end

  test "does not pause segments during working hours" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    @segment.update!(started_at: tz.local(2026, 2, 4, 10, 0))

    travel_to tz.local(2026, 2, 4, 15, 0) do
      WorkingHoursBoundaryJob.perform_now(@account.id)
    end

    @segment.reload
    assert_nil @segment.stopped_at, "Segment should stay open during working hours"
  end

  test "skips users with active after-hours override" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    @segment.update!(started_at: tz.local(2026, 2, 4, 14, 0))
    @membership.update!(after_hours_until: tz.local(2026, 2, 4, 21, 0))

    travel_to tz.local(2026, 2, 4, 19, 0) do
      WorkingHoursBoundaryJob.perform_now(@account.id)
    end

    @segment.reload
    assert_nil @segment.stopped_at, "Segment should stay open when override is active"
  end

  test "creates working_hours_auto_pause event" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    @segment.update!(started_at: tz.local(2026, 2, 4, 14, 0))

    travel_to tz.local(2026, 2, 4, 19, 0) do
      WorkingHoursBoundaryJob.perform_now(@account.id)
    end

    event = @account.events.find_by(action: "working_hours_auto_pause")
    assert event, "Should create auto_pause event"
    assert_equal @card.id, event.card_id
    assert_equal @user.id, event.actor_id
  end

  test "removes sys:active tag from card" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    @segment.update!(started_at: tz.local(2026, 2, 4, 14, 0))

    travel_to tz.local(2026, 2, 4, 19, 0) do
      WorkingHoursBoundaryJob.perform_now(@account.id)
    end

    @card.reload
    assert_not @card.active?, "Card should no longer be active"
  end

  test "uses per-user working hours override" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    @segment.update!(started_at: tz.local(2026, 2, 4, 14, 0))

    # User has override with later end_hour (20:00)
    @membership.update!(working_hours_override: {
      "timezone" => "Asia/Jerusalem",
      "days" => [0, 1, 2, 3, 4],
      "start_hour" => 9,
      "end_hour" => 20
    })

    # 19:00 is past account boundary (18:00) but before user boundary (20:00)
    travel_to tz.local(2026, 2, 4, 19, 0) do
      WorkingHoursBoundaryJob.perform_now(@account.id)
    end

    @segment.reload
    assert_nil @segment.stopped_at, "Segment should stay open — user boundary is 20:00"
  end

  test "closes segment at boundary time not current time" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    @segment.update!(started_at: tz.local(2026, 2, 4, 10, 0))

    # Job runs well after boundary (e.g. 22:00)
    travel_to tz.local(2026, 2, 4, 22, 0) do
      WorkingHoursBoundaryJob.perform_now(@account.id)
    end

    @segment.reload
    assert_not_nil @segment.stopped_at
    stopped_local = @segment.stopped_at.in_time_zone(tz)
    assert_equal 18, stopped_local.hour, "Should close at 18:00 boundary, not 22:00"
  end

  test "processes all accounts when no account_id given" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    @segment.update!(started_at: tz.local(2026, 2, 4, 14, 0))

    travel_to tz.local(2026, 2, 4, 19, 0) do
      WorkingHoursBoundaryJob.perform_now
    end

    @segment.reload
    assert_not_nil @segment.stopped_at
  end
end
