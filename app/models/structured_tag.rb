class StructuredTag
  attr_reader :raw, :parts

  def initialize(raw)
    @raw = raw.to_s
    @parts = @raw.split(":")
  end

  def structured?
    parts.length > 1
  end

  def prefix
    parts.first
  end

  # For tags like blocked:card:2, depends:card:5
  def card_ref?
    parts.length == 3 && parts[1] == "card" && parts[2].match?(/\A\d+\z/)
  end

  def card_ref_id
    parts[2].to_i if card_ref?
  end

  # Does this tag match a pattern like "blocked:card:*" or "blocked:*"?
  # Wildcards only match at the end or in specific positions.
  def matches?(pattern)
    pattern_parts = pattern.split(":")
    return false if pattern_parts.length > parts.length

    pattern_parts.each_with_index do |pp, i|
      next if pp == "*"
      return false if pp != parts[i]
    end

    # If pattern is shorter than tag, only match if last pattern part was *
    return false if pattern_parts.length < parts.length && pattern_parts.last != "*"

    true
  end

  def to_s
    raw
  end
end
