module ApplicationHelper
  def linkify_mentions(html, account)
    return html unless html.present?

    html.to_s.gsub(/@(\d+)/) do |match|
      card_id = $1.to_i
      card = account.cards.find_by(id: card_id)
      if card
        link_to(match, card_path(account.slug, card), class: "mention-link")
      else
        match
      end
    end.html_safe
  end

  # Render a structured tag as human-readable HTML.
  # card:N refs become links. Other structured tags show prefix + value.
  def render_structured_tag(tag_name, account)
    st = StructuredTag.new(tag_name)

    if st.card_ref?
      card = account.cards.find_by(id: st.card_ref_id)
      label = card ? link_to(card.title, card_path(account.slug, card)) : "##{st.card_ref_id}"
      "#{st.prefix} #{label}".html_safe
    elsif st.structured?
      tag_name
    else
      tag_name
    end
  end

end
