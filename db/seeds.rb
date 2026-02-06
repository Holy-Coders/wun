account = Account.find_or_create_by!(slug: "demo") do |a|
  a.name = "Demo Team"
  a.working_hours = {
    "timezone" => "Asia/Jerusalem",
    "days" => [0, 1, 2, 3, 4],
    "start_hour" => 9,
    "end_hour" => 18
  }
end

user = User.find_or_create_by!(account: account, email_address: "demo@wun.app") do |u|
  u.name = "Demo User"
  u.password = "password"
end

Membership.find_or_create_by!(account: account, user: user) do |m|
  m.role = "admin"
end

# System rules
Rule.find_or_create_by!(account: account, name: "Activate removes done") do |r|
  r.trigger = "tag_added"
  r.action_type = "remove_tag"
  r.action_config = { "when_tag" => "sys:active", "tag" => "sys:done" }
  r.position = 0
  r.system = true
end


engine = RuleEngine.new(account: account, actor: user)

# Create some cards
card1 = engine.create_card(title: "Design the card layout", body: "<p>Sketch the Active card and On Deck queue.</p>")
card2 = engine.create_card(title: "Build the rule engine", body: "<p>Event-driven rules that react to tag changes.</p>")
card3 = engine.create_card(title: "Set up authentication", body: "<p>Basic session-based auth with has_secure_password.</p>")

# Add some user tags
engine.add_tag(card: card1, tag_name: "design")
engine.add_tag(card: card2, tag_name: "backend")
engine.add_tag(card: card3, tag_name: "backend")

# Activate card1 — this is the one being worked on
engine.activate(card: card1)

puts "Seeded: account=#{account.slug}, user=#{user.email_address}, cards=#{account.cards.count}"
