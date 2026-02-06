require "test_helper"

class RuleEngineTest < ActiveSupport::TestCase
  setup do
    @account = Account.create!(name: "Test", slug: "test")
    @user = @account.users.create!(name: "Alice", email_address: "alice@test.com", password: "password")
    Membership.create!(account: @account, user: @user, role: "member")
    @engine = RuleEngine.new(account: @account, actor: @user)
  end

  test "create_card creates a card and fires event" do
    card = @engine.create_card(title: "My card")

    assert_equal "My card", card.title
    assert_equal @user, card.creator
    assert_equal @account, card.account

    event = Event.last
    assert_equal "card_created", event.action
    assert_equal card, event.card
  end

  test "activate sets sys:active tag on card" do
    card = @engine.create_card(title: "Work on this")

    @engine.activate(card: card)

    assert card.reload.active?
  end

  test "only one card can be active per user" do
    card1 = @engine.create_card(title: "First")
    card2 = @engine.create_card(title: "Second")

    @engine.activate(card: card1)
    assert card1.reload.active?

    @engine.activate(card: card2)
    assert card2.reload.active?
    refute card1.reload.active?
  end

  test "deactivate removes sys:active tag" do
    card = @engine.create_card(title: "Working")
    @engine.activate(card: card)
    assert card.reload.active?

    @engine.deactivate(card: card)
    refute card.reload.active?
  end

  test "mark_done removes active and adds done" do
    card = @engine.create_card(title: "Finish this")
    @engine.activate(card: card)

    @engine.mark_done(card: card)

    refute card.reload.active?
    assert card.reload.done?
  end

  test "add_tag creates tag and tagging" do
    card = @engine.create_card(title: "Tagged")

    @engine.add_tag(card: card, tag_name: "backend")

    assert card.tags.exists?(name: "backend")
    assert_equal false, Tag.find_by(name: "backend").system?
  end

  test "system tags are marked as system" do
    card = @engine.create_card(title: "System")

    @engine.add_tag(card: card, tag_name: "sys:active")

    tag = Tag.find_by(name: "sys:active")
    assert tag.system?
  end

  test "remove_tag removes the tagging" do
    card = @engine.create_card(title: "Untagged")
    @engine.add_tag(card: card, tag_name: "backend")
    assert card.tags.exists?(name: "backend")

    @engine.remove_tag(card: card, tag_name: "backend")
    refute card.reload.tags.exists?(name: "backend")
  end

  test "add_comment creates comment and fires event" do
    card = @engine.create_card(title: "Commented")

    comment = @engine.add_comment(card: card, body: "Looks good")

    assert_equal "Looks good", comment.body.to_plain_text
    assert_equal @user, comment.creator

    event = Event.where(action: "comment_added").last
    assert_equal card, event.card
  end

  test "events are append-only log" do
    card = @engine.create_card(title: "Logged")
    @engine.add_tag(card: card, tag_name: "backend")
    @engine.activate(card: card)
    @engine.deactivate(card: card)

    events = card.events.order(:created_at).pluck(:action)
    assert_includes events, "card_created"
    assert_includes events, "tag_added"
    assert_includes events, "tag_removed"
  end

  # --- Activity segments ---

  test "activating a card starts an activity segment" do
    card = @engine.create_card(title: "Track this")

    @engine.activate(card: card)

    segment = ActivitySegment.last
    assert_equal card, segment.card
    assert_equal @user, segment.user
    assert_not_nil segment.started_at
    assert_nil segment.stopped_at
    assert segment.open?
  end

  test "deactivating a card stops the activity segment" do
    card = @engine.create_card(title: "Stop this")
    @engine.activate(card: card)

    @engine.deactivate(card: card)

    segment = ActivitySegment.last
    assert_not_nil segment.stopped_at
    refute segment.open?
    assert segment.duration > 0
  end

  test "switching cards closes old segment and opens new one" do
    card1 = @engine.create_card(title: "First")
    card2 = @engine.create_card(title: "Second")

    @engine.activate(card: card1)
    @engine.activate(card: card2)

    segments = ActivitySegment.order(:started_at).to_a
    assert_equal 2, segments.length

    assert_equal card1, segments[0].card
    refute segments[0].open?

    assert_equal card2, segments[1].card
    assert segments[1].open?
  end

  test "no overlapping segments per user" do
    card1 = @engine.create_card(title: "A")
    card2 = @engine.create_card(title: "B")
    card3 = @engine.create_card(title: "C")

    @engine.activate(card: card1)
    @engine.activate(card: card2)
    @engine.activate(card: card3)

    open_segments = ActivitySegment.open.for_user(@user).count
    assert_equal 1, open_segments
  end

  test "mark_done closes the activity segment" do
    card = @engine.create_card(title: "Done")
    @engine.activate(card: card)

    @engine.mark_done(card: card)

    segment = ActivitySegment.where(card: card).last
    refute segment.open?
  end

  test "rapid switching produces clean segments" do
    cards = 5.times.map { |i| @engine.create_card(title: "Card #{i}") }

    cards.each { |c| @engine.activate(card: c) }

    segments = ActivitySegment.order(:started_at).to_a
    assert_equal 5, segments.length

    # All but last should be closed
    segments[0...-1].each { |s| refute s.open?, "Segment for #{s.card.title} should be closed" }
    assert segments.last.open?

    # No overlaps — each segment's stopped_at <= next segment's started_at
    segments.each_cons(2) do |a, b|
      assert a.stopped_at <= b.started_at, "Segments overlap"
    end
  end

  test "done card is not active" do
    card = @engine.create_card(title: "Finished")
    @engine.activate(card: card)
    @engine.mark_done(card: card)

    refute card.reload.active?
    assert card.reload.done?
    assert ActivitySegment.where(card: card).last.stopped_at.present?
  end

  test "activating a done card removes sys:done via rule" do
    Rule.create!(
      account: @account, name: "Activate removes done", trigger: "tag_added",
      action_type: "remove_tag",
      action_config: { "when_tag" => "sys:active", "tag" => "sys:done" },
      position: 0
    )

    card = @engine.create_card(title: "Reopened")
    @engine.activate(card: card)
    @engine.mark_done(card: card)

    assert card.reload.done?
    refute card.reload.active?

    @engine.activate(card: card)

    assert card.reload.active?
    refute card.reload.done?
  end

  test "when_tag condition prevents rule from firing on other tags" do
    Rule.create!(
      account: @account, name: "Only on active", trigger: "tag_added",
      action_type: "remove_tag",
      action_config: { "when_tag" => "sys:active", "tag" => "sys:done" },
      position: 0
    )

    card = @engine.create_card(title: "Conditional")
    @engine.mark_done(card: card)
    @engine.add_tag(card: card, tag_name: "backend")

    # Adding "backend" should NOT trigger the rule (when_tag is sys:active)
    assert card.reload.done?
  end

  # --- Rule execution ---

  test "rules execute in position order" do
    card = @engine.create_card(title: "Ruled")

    rule_b = Rule.create!(
      account: @account, name: "Second", trigger: "tag_added",
      action_type: "add_tag", action_config: { "tag" => "second" }, position: 2
    )
    rule_a = Rule.create!(
      account: @account, name: "First", trigger: "tag_added",
      action_type: "add_tag", action_config: { "tag" => "first" }, position: 1
    )

    @engine.add_tag(card: card, tag_name: "trigger-me")

    executed = Event.where(action: "rule_executed").order(:created_at).pluck(:metadata)
    rule_names = executed.map { |m| m["rule_name"] }

    assert_equal "First", rule_names.first
  end

  test "rule_executed events are logged" do
    card = @engine.create_card(title: "Logged rule")

    Rule.create!(
      account: @account, name: "Auto-tag", trigger: "card_created",
      action_type: "add_tag", action_config: { "tag" => "auto" }, position: 0
    )

    card2 = @engine.create_card(title: "Another")

    rule_events = Event.where(action: "rule_executed", card: card2)
    assert rule_events.any?
    assert_equal "Auto-tag", rule_events.first.metadata["rule_name"]
  end

  # --- Comment-driven rules ---

  test "keyword_tag rule adds tag when comment contains keyword" do
    Rule.create!(
      account: @account, name: "Block on blocked", trigger: "comment_added",
      action_type: "keyword_tag",
      action_config: { "keyword" => "blocked", "tag" => "blocked" },
      position: 0
    )

    card = @engine.create_card(title: "Keyword test")
    @engine.add_comment(card: card, body: "This is blocked by API team")

    assert card.reload.tags.exists?(name: "blocked")
  end

  test "keyword_tag rule is case insensitive" do
    Rule.create!(
      account: @account, name: "Urgent", trigger: "comment_added",
      action_type: "keyword_tag",
      action_config: { "keyword" => "urgent", "tag" => "urgent" },
      position: 0
    )

    card = @engine.create_card(title: "Case test")
    @engine.add_comment(card: card, body: "URGENT: needs review")

    assert card.reload.tags.exists?(name: "urgent")
  end

  test "keyword_tag rule does not fire without keyword" do
    Rule.create!(
      account: @account, name: "Block on blocked", trigger: "comment_added",
      action_type: "keyword_tag",
      action_config: { "keyword" => "blocked", "tag" => "blocked" },
      position: 0
    )

    card = @engine.create_card(title: "No keyword")
    @engine.add_comment(card: card, body: "Everything looks fine")

    refute card.reload.tags.exists?(name: "blocked")
  end

  test "keyword_tag rule logs rule_executed event" do
    rule = Rule.create!(
      account: @account, name: "Block rule", trigger: "comment_added",
      action_type: "keyword_tag",
      action_config: { "keyword" => "blocked", "tag" => "blocked" },
      position: 0
    )

    card = @engine.create_card(title: "Event log test")
    @engine.add_comment(card: card, body: "This is blocked")

    rule_event = Event.where(action: "rule_executed", card: card).last
    assert_equal "Block rule", rule_event.metadata["rule_name"]
    assert_equal "blocked", rule_event.metadata.dig("result", "tag")
  end

  test "different users can each have one active card" do
    user2 = @account.users.create!(name: "Bob", email_address: "bob@test.com", password: "password")
    Membership.create!(account: @account, user: user2, role: "member")
    engine2 = RuleEngine.new(account: @account, actor: user2)

    card1 = @engine.create_card(title: "Alice work")
    card2 = engine2.create_card(title: "Bob work")

    @engine.activate(card: card1)
    engine2.activate(card: card2)

    assert card1.reload.active?
    assert card2.reload.active?
  end

  # --- Loop prevention ---

  test "rule engine halts when rules would recurse beyond max depth" do
    # Create two rules that trigger each other:
    # Adding "ping" triggers adding "pong", adding "pong" triggers adding "ping"
    # Without loop prevention, this would stack overflow.
    Rule.create!(
      account: @account, name: "Ping adds pong", trigger: "tag_added",
      action_type: "add_tag",
      action_config: { "when_tag" => "ping", "tag" => "pong" },
      position: 0
    )
    Rule.create!(
      account: @account, name: "Pong adds ping", trigger: "tag_added",
      action_type: "add_tag",
      action_config: { "when_tag" => "pong", "tag" => "ping" },
      position: 1
    )

    card = @engine.create_card(title: "Loop test")

    # Should not raise — the engine should halt gracefully
    assert_nothing_raised do
      @engine.add_tag(card: card, tag_name: "ping")
    end

    # Both tags should be present (the first cycle completes)
    assert card.reload.tags.exists?(name: "ping")
    assert card.reload.tags.exists?(name: "pong")
  end

  # --- Structured tag pattern matching in rules ---

  test "when_tag_matches fires rule on matching structured tag" do
    Rule.create!(
      account: @account, name: "Blocked adds attention", trigger: "tag_added",
      action_type: "add_tag",
      action_config: { "when_tag_matches" => "blocked:*", "tag" => "needs-attention" },
      position: 0
    )

    card1 = @engine.create_card(title: "Blocker")
    card2 = @engine.create_card(title: "Blocked card")
    @engine.add_tag(card: card2, tag_name: "blocked:card:#{card1.id}")

    assert card2.reload.tags.exists?(name: "needs-attention")
  end

  test "when_tag_matches does not fire on non-matching tag" do
    Rule.create!(
      account: @account, name: "Blocked adds attention", trigger: "tag_added",
      action_type: "add_tag",
      action_config: { "when_tag_matches" => "blocked:*", "tag" => "needs-attention" },
      position: 0
    )

    card = @engine.create_card(title: "Normal card")
    @engine.add_tag(card: card, tag_name: "priority:high")

    assert_not card.reload.tags.exists?(name: "needs-attention")
  end

  test "when_tag_matches works with specific pattern" do
    Rule.create!(
      account: @account, name: "Depends adds waiting", trigger: "tag_added",
      action_type: "add_tag",
      action_config: { "when_tag_matches" => "depends:card:*", "tag" => "waiting" },
      position: 0
    )

    card1 = @engine.create_card(title: "Dependency")
    card2 = @engine.create_card(title: "Dependent")
    @engine.add_tag(card: card2, tag_name: "depends:card:#{card1.id}")

    assert card2.reload.tags.exists?(name: "waiting")
  end

  test "when_tag_matches fires on tag_removed too" do
    Rule.create!(
      account: @account, name: "Unblocked removes attention", trigger: "tag_removed",
      action_type: "remove_tag",
      action_config: { "when_tag_matches" => "blocked:*", "tag" => "needs-attention" },
      position: 0
    )

    card1 = @engine.create_card(title: "Blocker")
    card2 = @engine.create_card(title: "Was blocked")
    @engine.add_tag(card: card2, tag_name: "needs-attention")
    @engine.add_tag(card: card2, tag_name: "blocked:card:#{card1.id}")

    @engine.remove_tag(card: card2, tag_name: "blocked:card:#{card1.id}")

    assert_not card2.reload.tags.exists?(name: "needs-attention")
  end
end
