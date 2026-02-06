class RuleEngine
  MAX_RULE_DEPTH = 10

  attr_reader :account, :actor

  def initialize(account:, actor:)
    @account = account
    @actor = actor
    @rule_depth = 0
  end

  # Create a card and fire the card_created event
  def create_card(title:, body: nil)
    next_position = (account.cards.where(creator: actor).maximum(:position) || 0) + 1
    card = account.cards.create!(title: title, body: body, creator: actor, position: next_position)
    fire_event(card: card, action: "card_created")
    process_rules(trigger: "card_created", card: card)
    card
  end

  # Add a tag to a card and fire tag_added event
  def add_tag(card:, tag_name:)
    tag = find_or_create_tag(tag_name)
    return if card.tags.include?(tag)

    card.taggings.create!(tag: tag, creator: actor)
    fire_event(card: card, action: "tag_added", metadata: { tag: tag_name })

    start_segment(card: card) if tag_name == "sys:active"

    process_rules(trigger: "tag_added", card: card, context: { tag: tag_name })
  end

  # Remove a tag from a card and fire tag_removed event
  def remove_tag(card:, tag_name:)
    tag = account.tags.find_by(name: tag_name)
    return unless tag

    tagging = card.taggings.find_by(tag: tag)
    return unless tagging

    tagging.destroy!
    fire_event(card: card, action: "tag_removed", metadata: { tag: tag_name })

    stop_segment if tag_name == "sys:active"

    process_rules(trigger: "tag_removed", card: card, context: { tag: tag_name })
  end

  # Add a comment to a card and fire comment_added event
  def add_comment(card:, body:)
    comment = card.comments.create!(body: body, creator: actor)
    resolve_mentions(comment, body)
    fire_event(card: card, action: "comment_added", metadata: { comment_id: comment.id })
    process_rules(trigger: "comment_added", card: card, context: { comment_body: body })
    comment
  end

  # Activate a card — enforces one-active-per-user constraint
  def activate(card:)
    deactivate_current_card
    add_tag(card: card, tag_name: "sys:active")
  end

  # Deactivate a card
  def deactivate(card:)
    remove_tag(card: card, tag_name: "sys:active")
  end

  # Mark a card as done — also deactivates it
  def mark_done(card:)
    remove_tag(card: card, tag_name: "sys:active")
    add_tag(card: card, tag_name: "sys:done")
  end

  private

  def resolve_mentions(comment, body)
    card_ids = body.to_s.scan(/@(\d+)/).flatten.map(&:to_i).uniq
    return if card_ids.empty?

    account.cards.where(id: card_ids).find_each do |mentioned_card|
      comment.mentions.create!(card: mentioned_card)
    end
  end

  def deactivate_current_card
    active_tag = account.tags.find_by(name: "sys:active")
    return unless active_tag

    current_tagging = Tagging
      .joins(:card)
      .where(tag: active_tag, cards: { creator_id: actor.id, account_id: account.id })
      .first

    return unless current_tagging

    current_card = current_tagging.card
    remove_tag(card: current_card, tag_name: "sys:active")
  end

  def start_segment(card:)
    stop_segment

    account.activity_segments.create!(
      card: card,
      user: actor,
      started_at: Time.current
    )
  end

  def stop_segment
    open_segment = account.activity_segments.open.for_user(actor).first
    open_segment&.update!(stopped_at: Time.current)
  end

  def find_or_create_tag(name)
    system = name.start_with?("sys:")
    account.tags.find_or_create_by!(name: name) do |tag|
      tag.system = system
    end
  end

  def fire_event(card:, action:, metadata: nil)
    account.events.create!(
      card: card,
      actor: actor,
      action: action,
      metadata: metadata
    )
  end

  def process_rules(trigger:, card:, context: {})
    @rule_depth += 1
    if @rule_depth > MAX_RULE_DEPTH
      Rails.logger.warn("[RuleEngine] Max rule depth (#{MAX_RULE_DEPTH}) exceeded — halting")
      @rule_depth -= 1
      return
    end

    rules = account.rules.for_trigger(trigger)

    rules.each do |rule|
      execute_rule(rule, card: card, context: context)
    end
  ensure
    @rule_depth -= 1
  end

  def execute_rule(rule, card:, context: {})
    return unless condition_met?(rule, context)

    result = case rule.action_type
    when "add_tag"
      target_tag = rule.action_config&.dig("tag")
      add_tag(card: card, tag_name: target_tag) if target_tag
      { action: "add_tag", tag: target_tag }
    when "remove_tag"
      target_tag = rule.action_config&.dig("tag")
      remove_tag(card: card, tag_name: target_tag) if target_tag
      { action: "remove_tag", tag: target_tag }
    when "keyword_tag"
      keyword = rule.action_config&.dig("keyword")
      target_tag = rule.action_config&.dig("tag")
      comment_body = context[:comment_body].to_s

      if keyword && target_tag && comment_body.downcase.include?(keyword.downcase)
        add_tag(card: card, tag_name: target_tag)
        { action: "keyword_tag", keyword: keyword, tag: target_tag }
      end
    when "keyword_remove_tag"
      keyword = rule.action_config&.dig("keyword")
      target_tag = rule.action_config&.dig("tag")
      comment_body = context[:comment_body].to_s

      if keyword && target_tag && comment_body.downcase.include?(keyword.downcase)
        remove_tag(card: card, tag_name: target_tag)
        { action: "keyword_remove_tag", keyword: keyword, tag: target_tag }
      end
    end

    fire_event(
      card: card,
      action: "rule_executed",
      metadata: { rule_id: rule.id, rule_name: rule.name, result: result }
    ) if result
  end

  def condition_met?(rule, context)
    when_tag = rule.action_config&.dig("when_tag")
    when_pattern = rule.action_config&.dig("when_tag_matches")

    if when_pattern
      tag = context[:tag]
      return false unless tag
      return StructuredTag.new(tag).matches?(when_pattern)
    end

    return true unless when_tag
    context[:tag] == when_tag
  end
end
