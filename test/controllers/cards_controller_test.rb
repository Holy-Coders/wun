require "test_helper"

class CardsControllerTest < ActionDispatch::IntegrationTest
  setup do
    @account = Account.create!(name: "Test", slug: "test")
    @user = @account.users.create!(name: "Alice", email_address: "alice@test.com", password: "password")
    Membership.create!(account: @account, user: @user, role: "member")

    # Sign in
    post session_path, params: {
      account_slug: "test",
      email_address: "alice@test.com",
      password: "password"
    }

    @engine = RuleEngine.new(account: @account, actor: @user)
  end

  test "index shows cards" do
    @engine.create_card(title: "My card")
    get account_root_path("test")
    assert_response :success
  end

  test "show displays a card" do
    card = @engine.create_card(title: "Detail card")
    get card_path("test", card)
    assert_response :success
  end

  test "create a new card" do
    assert_difference "Card.count" do
      post cards_path("test"), params: { card: { title: "New card" } }
    end
    assert_redirected_to card_path("test", Card.last)
  end

  test "activate a card" do
    card = @engine.create_card(title: "Activate me")
    post activate_card_path("test", card)
    assert card.reload.active?
  end

  test "deactivate a card" do
    card = @engine.create_card(title: "Deactivate me")
    @engine.activate(card: card)
    post deactivate_card_path("test", card)
    refute card.reload.active?
  end

  test "mark card done" do
    card = @engine.create_card(title: "Done card")
    @engine.activate(card: card)
    post done_card_path("test", card)
    assert card.reload.done?
  end

  # --- Tenant guardrails ---

  test "cannot access card from another account" do
    other = Account.create!(name: "Other", slug: "other")
    other_user = other.users.create!(name: "Bob", email_address: "bob@other.com", password: "password")
    Membership.create!(account: other, user: other_user, role: "member")

    other_engine = RuleEngine.new(account: other, actor: other_user)
    other_card = other_engine.create_card(title: "Secret card")

    # Try to access other account's card through our account URL
    get card_path("test", other_card)
    assert_response :not_found
  end

  test "cannot activate card from another account" do
    other = Account.create!(name: "Other", slug: "other")
    other_user = other.users.create!(name: "Bob", email_address: "bob@other.com", password: "password")
    Membership.create!(account: other, user: other_user, role: "member")

    other_engine = RuleEngine.new(account: other, actor: other_user)
    other_card = other_engine.create_card(title: "Secret card")

    post activate_card_path("test", other_card)
    assert_response :not_found
  end

  test "unauthenticated user is redirected" do
    delete destroy_session_path
    card = @engine.create_card(title: "Protected")
    get card_path("test", card)
    assert_redirected_to new_session_path
  end
end
