require "test_helper"

class ActivityReportTest < ActiveSupport::TestCase
  setup do
    @account = Account.create!(name: "Test", slug: "test")
    @alice = @account.users.create!(name: "Alice", email_address: "alice@test.com", password: "password")
    @bob = @account.users.create!(name: "Bob", email_address: "bob@test.com", password: "password")
    Membership.create!(account: @account, user: @alice, role: "member")
    Membership.create!(account: @account, user: @bob, role: "member")

    @engine_alice = RuleEngine.new(account: @account, actor: @alice)
    @engine_bob = RuleEngine.new(account: @account, actor: @bob)
    @report = ActivityReport.new(account: @account)
  end

  test "time_per_card returns total focused seconds per card" do
    card = @engine_alice.create_card(title: "Tracked")
    @engine_alice.add_tag(card: card, tag_name: "backend")

    # Create a closed segment manually for precise timing
    @account.activity_segments.create!(
      card: card, user: @alice,
      started_at: 30.minutes.ago,
      stopped_at: 10.minutes.ago
    )

    result = @report.time_per_card
    assert result[card] >= 1190 # ~20 minutes in seconds, with tolerance
  end

  test "time_per_tag aggregates across cards" do
    card1 = @engine_alice.create_card(title: "Card A")
    card2 = @engine_alice.create_card(title: "Card B")
    @engine_alice.add_tag(card: card1, tag_name: "backend")
    @engine_alice.add_tag(card: card2, tag_name: "backend")

    @account.activity_segments.create!(card: card1, user: @alice, started_at: 60.minutes.ago, stopped_at: 30.minutes.ago)
    @account.activity_segments.create!(card: card2, user: @alice, started_at: 30.minutes.ago, stopped_at: Time.current)

    result = @report.time_per_tag
    assert result["backend"] >= 3590 # ~60 minutes total
  end

  test "avg_focus_per_user returns average segment duration" do
    card1 = @engine_alice.create_card(title: "A1")
    card2 = @engine_alice.create_card(title: "A2")

    # Two segments for Alice: 10 min and 20 min => avg 15 min = 900s
    @account.activity_segments.create!(card: card1, user: @alice, started_at: 40.minutes.ago, stopped_at: 30.minutes.ago)
    @account.activity_segments.create!(card: card2, user: @alice, started_at: 20.minutes.ago, stopped_at: Time.current)

    result = @report.avg_focus_per_user
    assert result[@alice] >= 890 # ~900 seconds average
  end

  test "time_for_user returns card breakdown" do
    card1 = @engine_alice.create_card(title: "My card 1")
    card2 = @engine_alice.create_card(title: "My card 2")

    @account.activity_segments.create!(card: card1, user: @alice, started_at: 30.minutes.ago, stopped_at: 20.minutes.ago)
    @account.activity_segments.create!(card: card2, user: @alice, started_at: 20.minutes.ago, stopped_at: 10.minutes.ago)

    result = @report.time_for_user(@alice)
    assert_equal 2, result.size
    assert result[card1] >= 590
    assert result[card2] >= 590
  end

  test "total_focused_time sums all closed segments" do
    card = @engine_alice.create_card(title: "Total")

    @account.activity_segments.create!(card: card, user: @alice, started_at: 60.minutes.ago, stopped_at: 30.minutes.ago)
    @account.activity_segments.create!(card: card, user: @alice, started_at: 20.minutes.ago, stopped_at: 10.minutes.ago)

    total = @report.total_focused_time
    assert total >= 2390 # ~40 minutes
  end

  test "open segments are excluded from reports" do
    card = @engine_alice.create_card(title: "Open")

    # One open segment — should not count
    @account.activity_segments.create!(card: card, user: @alice, started_at: 10.minutes.ago, stopped_at: nil)

    assert_equal 0, @report.total_focused_time
    assert_empty @report.time_per_card
  end
end
