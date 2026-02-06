require "test_helper"

class CardBlockedTest < ActiveSupport::TestCase
  setup do
    @account = Account.create!(name: "Block Test", slug: "block-test")
    @user = @account.users.create!(name: "Blocker", email_address: "block@test.com", password: "password")
    Membership.create!(account: @account, user: @user, role: "member")
    @engine = RuleEngine.new(account: @account, actor: @user)
  end

  # --- Generic structured tag methods ---

  test "has_tag_prefix? detects any prefix" do
    card = @engine.create_card(title: "Tagged")
    @engine.add_tag(card: card, tag_name: "priority:high")

    assert card.has_tag_prefix?("priority")
    assert_not card.has_tag_prefix?("blocked")
  end

  test "tags_with_prefix returns matching tag names" do
    card = @engine.create_card(title: "Multi")
    @engine.add_tag(card: card, tag_name: "team:backend")
    @engine.add_tag(card: card, tag_name: "team:frontend")
    @engine.add_tag(card: card, tag_name: "priority:high")

    team_tags = card.tags_with_prefix("team")
    assert_equal 2, team_tags.size
    assert_includes team_tags, "team:backend"
    assert_includes team_tags, "team:frontend"
  end

  test "card_refs_for extracts card IDs from prefix:card:N tags" do
    card1 = @engine.create_card(title: "Dep A")
    card2 = @engine.create_card(title: "Dep B")
    card3 = @engine.create_card(title: "Depends on both")

    @engine.add_tag(card: card3, tag_name: "depends:card:#{card1.id}")
    @engine.add_tag(card: card3, tag_name: "depends:card:#{card2.id}")

    refs = card3.card_refs_for("depends")
    assert_equal 2, refs.size
    assert_includes refs, card1.id
    assert_includes refs, card2.id
  end

  # --- blocked? convenience (uses generics) ---

  test "card is not blocked by default" do
    card = @engine.create_card(title: "Free card")
    assert_not card.blocked?
  end

  test "card is blocked when tagged blocked:card:N" do
    card1 = @engine.create_card(title: "Blocker")
    card2 = @engine.create_card(title: "Blocked")
    @engine.add_tag(card: card2, tag_name: "blocked:card:#{card1.id}")

    assert card2.blocked?
  end

  test "blocking_card_ids returns array of blocking card IDs" do
    card1 = @engine.create_card(title: "Blocker A")
    card2 = @engine.create_card(title: "Blocker B")
    card3 = @engine.create_card(title: "Blocked")

    @engine.add_tag(card: card3, tag_name: "blocked:card:#{card1.id}")
    @engine.add_tag(card: card3, tag_name: "blocked:card:#{card2.id}")

    assert_includes card3.blocking_card_ids, card1.id
    assert_includes card3.blocking_card_ids, card2.id
    assert_equal 2, card3.blocking_card_ids.size
  end

  test "removing blocked tag unblocks card" do
    card1 = @engine.create_card(title: "Blocker")
    card2 = @engine.create_card(title: "Was blocked")

    @engine.add_tag(card: card2, tag_name: "blocked:card:#{card1.id}")
    assert card2.blocked?

    @engine.remove_tag(card: card2, tag_name: "blocked:card:#{card1.id}")
    assert_not card2.reload.blocked?
  end

  test "structured tags fire events like any other tag" do
    card1 = @engine.create_card(title: "Blocker")
    card2 = @engine.create_card(title: "Blocked")

    @engine.add_tag(card: card2, tag_name: "blocked:card:#{card1.id}")

    event = @account.events.where(card: card2, action: "tag_added").last
    assert_equal "blocked:card:#{card1.id}", event.metadata["tag"]
  end
end
