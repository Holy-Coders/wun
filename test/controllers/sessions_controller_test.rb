require "test_helper"

class SessionsControllerTest < ActionDispatch::IntegrationTest
  setup do
    @account = Account.create!(name: "Test", slug: "test")
    @user = @account.users.create!(
      name: "Alice",
      email_address: "alice@test.com",
      password: "password"
    )
    Membership.create!(account: @account, user: @user, role: "admin")
  end

  test "shows sign in form" do
    get new_session_path
    assert_response :success
  end

  test "signs in with valid credentials" do
    post session_path, params: {
      account_slug: "test",
      email_address: "alice@test.com",
      password: "password"
    }
    assert_redirected_to account_root_path("test")
    follow_redirect!
    assert_response :success
  end

  test "rejects invalid password" do
    post session_path, params: {
      account_slug: "test",
      email_address: "alice@test.com",
      password: "wrong"
    }
    assert_response :unprocessable_entity
  end

  test "rejects invalid account" do
    post session_path, params: {
      account_slug: "nope",
      email_address: "alice@test.com",
      password: "password"
    }
    assert_response :unprocessable_entity
  end

  test "signs out" do
    post session_path, params: {
      account_slug: "test",
      email_address: "alice@test.com",
      password: "password"
    }

    delete destroy_session_path
    assert_redirected_to root_path
  end

  test "unauthenticated user is redirected to sign in" do
    get account_root_path("test")
    assert_redirected_to new_session_path
  end

  test "cannot access account you are not a member of" do
    other = Account.create!(name: "Other", slug: "other")
    other_user = other.users.create!(name: "Bob", email_address: "bob@other.com", password: "password")
    Membership.create!(account: other, user: other_user, role: "member")

    # Sign in as Alice on test account
    post session_path, params: {
      account_slug: "test",
      email_address: "alice@test.com",
      password: "password"
    }

    # Try to access other account — Alice is not a member
    get account_root_path("other")
    assert_redirected_to new_session_path
  end
end
