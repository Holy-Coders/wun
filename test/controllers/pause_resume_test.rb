require "test_helper"

class PauseResumeTest < ActionDispatch::IntegrationTest
  setup do
    @account = Account.create!(name: "Pause", slug: "pause-test", working_hours: {
      "timezone" => "Asia/Jerusalem",
      "days" => [0, 1, 2, 3, 4],
      "start_hour" => 9,
      "end_hour" => 18
    })
    @user = @account.users.create!(name: "Pauser", email_address: "pause@test.com", password: "password")
    Membership.create!(account: @account, user: @user, role: "member")

    post session_url, params: { account_slug: @account.slug, email_address: @user.email_address, password: "password" }
  end

  test "shows manual pause reason after deactivation" do
    engine = RuleEngine.new(account: @account, actor: @user)
    card = engine.create_card(title: "Focus card")
    engine.activate(card: card)
    engine.deactivate(card: card)

    get account_root_url(@account.slug)
    assert_response :success
    assert_match "Nothing is active", response.body
  end

  test "shows resume button for last active card" do
    engine = RuleEngine.new(account: @account, actor: @user)
    card = engine.create_card(title: "Resumable card")
    engine.activate(card: card)
    engine.deactivate(card: card)

    get account_root_url(@account.slug)
    assert_response :success
    assert_match "Resumable card", response.body
    assert_match "Resume", response.body
  end

  test "does not suggest resuming a done card" do
    engine = RuleEngine.new(account: @account, actor: @user)
    card = engine.create_card(title: "Finished card")
    engine.activate(card: card)
    engine.mark_done(card: card)

    get account_root_url(@account.slug)
    assert_response :success
    assert_no_match "Finished card", response.body
    assert_match "Choose one card from the deck", response.body
  end

  test "shows idle pause reason after idle deactivation" do
    engine = RuleEngine.new(account: @account, actor: @user)
    card = engine.create_card(title: "Idle card")
    engine.activate(card: card)

    # Simulate idle deactivation via the endpoint
    post idle_deactivate_card_url(@account.slug, card), params: { idle_since: 15.minutes.ago.iso8601 }

    get account_root_url(@account.slug)
    assert_response :success
    assert_match "Stepped away", response.body
  end

  test "shows working hours pause reason after auto-pause" do
    engine = RuleEngine.new(account: @account, actor: @user)
    card = engine.create_card(title: "WH card")
    engine.activate(card: card)

    # Simulate working hours auto-pause event
    engine.deactivate(card: card)
    @account.events.create!(
      card: card,
      actor: @user,
      action: "working_hours_auto_pause",
      metadata: { boundary_time: Time.current.iso8601 }
    )

    get account_root_url(@account.slug)
    assert_response :success
    assert_match "After hours", response.body
  end

  test "shows default message when no prior activity" do
    get account_root_url(@account.slug)
    assert_response :success
    assert_match "Nothing is active", response.body
  end

  test "resume activates the card" do
    engine = RuleEngine.new(account: @account, actor: @user)
    card = engine.create_card(title: "Resume me")
    engine.activate(card: card)
    engine.deactivate(card: card)

    post activate_card_url(@account.slug, card)
    follow_redirect!

    card.reload
    assert card.active?, "Card should be active after resume"
  end
end
