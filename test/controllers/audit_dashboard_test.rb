require "test_helper"

class AuditDashboardTest < ActionDispatch::IntegrationTest
  setup do
    @account = Account.create!(name: "Audit", slug: "audit-dash")
    @admin = @account.users.create!(name: "Admin", email_address: "admin@audit.com", password: "password")
    Membership.create!(account: @account, user: @admin, role: "admin")

    @member = @account.users.create!(name: "Member", email_address: "member@audit.com", password: "password")
    Membership.create!(account: @account, user: @member, role: "member")
  end

  test "admin can view audit page" do
    post session_url, params: { account_slug: @account.slug, email_address: @admin.email_address, password: "password" }
    get audit_dashboard_url(@account.slug)
    assert_response :success
  end

  test "member is rejected from audit page" do
    post session_url, params: { account_slug: @account.slug, email_address: @member.email_address, password: "password" }
    get audit_dashboard_url(@account.slug)
    assert_redirected_to account_root_url(@account.slug)
  end

  test "admin can trigger repair" do
    post session_url, params: { account_slug: @account.slug, email_address: @admin.email_address, password: "password" }
    post repair_dashboard_url(@account.slug)
    assert_redirected_to audit_dashboard_url(@account.slug)
  end
end
