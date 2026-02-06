require "test_helper"

class TaggingsControllerTest < ActionDispatch::IntegrationTest
  setup do
    @account = Account.create!(name: "Test", slug: "test")
    @user = @account.users.create!(name: "Alice", email_address: "alice@test.com", password: "password")
    Membership.create!(account: @account, user: @user, role: "member")

    post session_path, params: {
      account_slug: "test",
      email_address: "alice@test.com",
      password: "password"
    }

    @engine = RuleEngine.new(account: @account, actor: @user)
    @card = @engine.create_card(title: "Tagged card")
  end

  test "cannot add sys: tag via form" do
    assert_no_difference "Tagging.count" do
      post card_taggings_path("test", @card), params: { tagging: { tag_name: "sys:active" } }
    end
    assert_redirected_to card_path("test", @card)
    assert_equal "System tags cannot be added manually", flash[:alert]
  end

  test "cannot add sys:done tag via form" do
    assert_no_difference "Tagging.count" do
      post card_taggings_path("test", @card), params: { tagging: { tag_name: "sys:done" } }
    end
    assert_equal "System tags cannot be added manually", flash[:alert]
  end

  test "can add normal tag" do
    post card_taggings_path("test", @card), params: { tagging: { tag_name: "backend" } }
    assert @card.reload.tags.exists?(name: "backend")
  end

  test "cannot remove system tag via destroy" do
    @engine.activate(card: @card)
    tagging = @card.taggings.joins(:tag).find_by(tags: { name: "sys:active" })

    delete card_tagging_path("test", @card, tagging)
    assert_equal "System tags cannot be removed manually", flash[:alert]
    assert @card.reload.active?
  end
end
