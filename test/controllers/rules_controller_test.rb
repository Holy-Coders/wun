require "test_helper"

class RulesControllerTest < ActionDispatch::IntegrationTest
  setup do
    @account = Account.create!(name: "Test", slug: "test")
    @admin = @account.users.create!(name: "Admin", email_address: "admin@test.com", password: "password")
    Membership.create!(account: @account, user: @admin, role: "admin")

    post session_path, params: {
      account_slug: "test",
      email_address: "admin@test.com",
      password: "password"
    }
  end

  test "index lists rules" do
    Rule.create!(account: @account, name: "Test rule", trigger: "tag_added",
      action_type: "add_tag", action_config: { "tag" => "auto" }, position: 0)

    get rules_path("test")
    assert_response :success
  end

  test "create a user rule" do
    assert_difference "Rule.count" do
      post rules_path("test"), params: { rule: {
        name: "My rule", trigger: "comment_added",
        action_type: "keyword_tag", position: 5,
        action_config: { tag: "urgent", keyword: "urgent" }
      } }
    end

    rule = Rule.last
    assert_equal false, rule.system?
    assert_redirected_to rules_path("test")
  end

  test "cannot delete system rule" do
    rule = Rule.create!(account: @account, name: "System", trigger: "tag_added",
      action_type: "add_tag", action_config: { "tag" => "auto" }, position: 0, system: true)

    assert_no_difference "Rule.count" do
      delete rule_path("test", rule)
    end
    assert_equal "System rules cannot be deleted", flash[:alert]
  end

  test "cannot edit system rule" do
    rule = Rule.create!(account: @account, name: "System", trigger: "tag_added",
      action_type: "add_tag", action_config: { "tag" => "auto" }, position: 0, system: true)

    get edit_rule_path("test", rule)
    assert_redirected_to rules_path("test")
  end

  test "member cannot access rules" do
    worker = @account.users.create!(name: "Worker", email_address: "worker@test.com", password: "password")
    Membership.create!(account: @account, user: worker, role: "member")

    delete destroy_session_path
    post session_path, params: {
      account_slug: "test",
      email_address: "worker@test.com",
      password: "password"
    }

    get rules_path("test")
    assert_redirected_to account_root_path("test")
  end
end
