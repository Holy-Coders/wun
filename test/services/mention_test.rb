require "test_helper"

class MentionTest < ActiveSupport::TestCase
  setup do
    @account = Account.create!(name: "Test", slug: "test")
    @user = @account.users.create!(name: "Alice", email_address: "alice@test.com", password: "password")
    Membership.create!(account: @account, user: @user, role: "member")
    @engine = RuleEngine.new(account: @account, actor: @user)
  end

  test "comment with @id creates mention" do
    card1 = @engine.create_card(title: "First")
    card2 = @engine.create_card(title: "Second")

    comment = @engine.add_comment(card: card1, body: "See @#{card2.id} for details")

    assert_equal 1, comment.mentions.count
    assert_equal card2, comment.mentions.first.card
  end

  test "comment with multiple mentions creates multiple records" do
    card1 = @engine.create_card(title: "First")
    card2 = @engine.create_card(title: "Second")
    card3 = @engine.create_card(title: "Third")

    comment = @engine.add_comment(card: card1, body: "See @#{card2.id} and @#{card3.id}")

    assert_equal 2, comment.mentions.count
    mentioned_ids = comment.mentions.pluck(:card_id).sort
    assert_equal [card2.id, card3.id].sort, mentioned_ids
  end

  test "comment with invalid card id creates no mention" do
    card = @engine.create_card(title: "Solo")

    comment = @engine.add_comment(card: card, body: "See @999999")

    assert_equal 0, comment.mentions.count
  end

  test "comment without mentions creates no records" do
    card = @engine.create_card(title: "Plain")

    comment = @engine.add_comment(card: card, body: "No mentions here")

    assert_equal 0, comment.mentions.count
  end

  test "duplicate mentions in same comment are deduplicated" do
    card1 = @engine.create_card(title: "First")
    card2 = @engine.create_card(title: "Second")

    comment = @engine.add_comment(card: card1, body: "See @#{card2.id} and again @#{card2.id}")

    assert_equal 1, comment.mentions.count
  end

  test "mentions are scoped to same account" do
    other = Account.create!(name: "Other", slug: "other")
    other_user = other.users.create!(name: "Bob", email_address: "bob@other.com", password: "password")
    Membership.create!(account: other, user: other_user, role: "member")
    other_engine = RuleEngine.new(account: other, actor: other_user)
    other_card = other_engine.create_card(title: "Other account card")

    card = @engine.create_card(title: "My card")
    comment = @engine.add_comment(card: card, body: "See @#{other_card.id}")

    assert_equal 0, comment.mentions.count
  end
end
