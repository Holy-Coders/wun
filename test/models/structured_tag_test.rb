require "test_helper"

class StructuredTagTest < ActiveSupport::TestCase
  test "plain tag is not structured" do
    st = StructuredTag.new("design")
    assert_not st.structured?
    assert_equal "design", st.prefix
  end

  test "colon-delimited tag is structured" do
    st = StructuredTag.new("blocked:card:2")
    assert st.structured?
    assert_equal "blocked", st.prefix
    assert_equal ["blocked", "card", "2"], st.parts
  end

  test "card_ref? detects prefix:card:N pattern" do
    assert StructuredTag.new("blocked:card:42").card_ref?
    assert StructuredTag.new("depends:card:7").card_ref?
    assert_not StructuredTag.new("blocked:card:").card_ref?
    assert_not StructuredTag.new("blocked:card:abc").card_ref?
    assert_not StructuredTag.new("priority:high").card_ref?
    assert_not StructuredTag.new("design").card_ref?
  end

  test "card_ref_id extracts the ID" do
    assert_equal 42, StructuredTag.new("blocked:card:42").card_ref_id
    assert_nil StructuredTag.new("priority:high").card_ref_id
  end

  test "matches? with exact match" do
    st = StructuredTag.new("blocked:card:2")
    assert st.matches?("blocked:card:2")
    assert_not st.matches?("blocked:card:3")
  end

  test "matches? with wildcard at end" do
    st = StructuredTag.new("blocked:card:2")
    assert st.matches?("blocked:card:*")
    assert st.matches?("blocked:*")
    assert_not st.matches?("depends:*")
  end

  test "matches? with wildcard in middle" do
    st = StructuredTag.new("blocked:card:2")
    assert st.matches?("blocked:*:2")
    assert_not st.matches?("blocked:*:3")
  end

  test "matches? rejects short pattern without wildcard" do
    st = StructuredTag.new("blocked:card:2")
    assert_not st.matches?("blocked:card")
    assert_not st.matches?("blocked")
  end

  test "matches? works for two-part tags" do
    st = StructuredTag.new("priority:high")
    assert st.matches?("priority:*")
    assert st.matches?("priority:high")
    assert_not st.matches?("priority:low")
    assert_not st.matches?("team:*")
  end

  test "to_s returns the raw tag" do
    assert_equal "blocked:card:2", StructuredTag.new("blocked:card:2").to_s
  end
end
