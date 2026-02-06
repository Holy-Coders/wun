require "test_helper"

class DashboardControllerTest < ActionDispatch::IntegrationTest
  setup do
    @account = Account.create!(name: "Test", slug: "test")
    @user = @account.users.create!(name: "Alice", email_address: "alice@test.com", password: "password")
    Membership.create!(account: @account, user: @user, role: "admin")

    post session_path, params: {
      account_slug: "test",
      email_address: "alice@test.com",
      password: "password"
    }

    @engine = RuleEngine.new(account: @account, actor: @user)
  end

  test "dashboard loads with no activity" do
    get dashboard_path("test")
    assert_response :success
  end

  test "dashboard shows activity data" do
    card = @engine.create_card(title: "Tracked card")
    @engine.add_tag(card: card, tag_name: "backend")
    @engine.activate(card: card)
    @engine.deactivate(card: card)

    get dashboard_path("test")
    assert_response :success
  end

  test "dashboard requires authentication" do
    delete destroy_session_path
    get dashboard_path("test")
    assert_redirected_to new_session_path
  end

  test "dashboard requires manager role" do
    # Create a member-only user
    worker = @account.users.create!(name: "Worker", email_address: "worker@test.com", password: "password")
    Membership.create!(account: @account, user: worker, role: "member")

    delete destroy_session_path
    post session_path, params: {
      account_slug: "test",
      email_address: "worker@test.com",
      password: "password"
    }

    get dashboard_path("test")
    assert_redirected_to account_root_path("test")
  end

  test "manager role can access dashboard" do
    manager = @account.users.create!(name: "Manager", email_address: "mgr@test.com", password: "password")
    Membership.create!(account: @account, user: manager, role: "manager")

    delete destroy_session_path
    post session_path, params: {
      account_slug: "test",
      email_address: "mgr@test.com",
      password: "password"
    }

    get dashboard_path("test")
    assert_response :success
  end
end
