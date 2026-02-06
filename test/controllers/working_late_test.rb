require "test_helper"

class WorkingLateTest < ActionDispatch::IntegrationTest
  setup do
    @account = Account.create!(name: "Test", slug: "test-wl", working_hours: {
      "timezone" => "Asia/Jerusalem",
      "days" => [0, 1, 2, 3, 4],
      "start_hour" => 9,
      "end_hour" => 18
    })
    @user = @account.users.create!(name: "Night Owl", email_address: "owl@test.com", password: "password")
    @membership = Membership.create!(account: @account, user: @user, role: "member")

    post session_url, params: { account_slug: @account.slug, email_address: @user.email_address, password: "password" }
  end

  test "working_late sets after_hours_until on membership" do
    post working_late_url(@account.slug)

    @membership.reload
    assert @membership.after_hours_override_active?, "Override should be active"
    assert_in_delta 2.hours.from_now, @membership.after_hours_until, 5.seconds
  end

  test "working_late creates working_hours_override event" do
    post working_late_url(@account.slug)

    event = @account.events.find_by(action: "working_hours_override")
    assert event, "Should create override event"
    assert_equal @user.id, event.actor_id
    assert_nil event.card_id, "Override event is account-level, no card"
  end

  test "working_late redirects with notice" do
    post working_late_url(@account.slug)

    assert_redirected_to account_root_url(@account.slug)
    follow_redirect!
    assert_match "Working late", flash[:notice]
  end
end
