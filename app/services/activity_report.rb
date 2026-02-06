class ActivityReport
  attr_reader :account, :since

  def initialize(account:, since: nil)
    @account = account
    @since = since
  end

  # Total focused time per card (in seconds)
  def time_per_card
    scoped_segments
      .group(:card_id)
      .sum("strftime('%s', stopped_at) - strftime('%s', started_at)")
      .transform_keys { |id| Card.find(id) }
  end

  # Total focused time per tag (in seconds, aggregated across cards with that tag)
  def time_per_tag
    result = {}

    scoped_segments
      .includes(card: :tags)
      .find_each do |segment|
        duration = segment.stopped_at - segment.started_at

        segment.card.tags.user_tags.each do |tag|
          result[tag.name] ||= 0.0
          result[tag.name] += duration
        end
      end

    result.sort_by { |_, v| -v }.to_h
  end

  # Average uninterrupted focus length per user (in seconds)
  def avg_focus_per_user
    scoped_segments
      .group(:user_id)
      .pluck(
        :user_id,
        Arel.sql("AVG(strftime('%s', stopped_at) - strftime('%s', started_at))")
      )
      .to_h
      .transform_keys { |id| User.find(id) }
  end

  # Total focused time per user (in seconds)
  def time_per_user
    scoped_segments
      .group(:user_id)
      .sum("strftime('%s', stopped_at) - strftime('%s', started_at)")
      .transform_keys { |id| User.find(id) }
  end

  # Focused time for a specific user (in seconds), with card breakdown
  def time_for_user(user)
    scoped_segments
      .where(user: user)
      .group(:card_id)
      .sum("strftime('%s', stopped_at) - strftime('%s', started_at)")
      .transform_keys { |id| Card.find(id) }
  end

  # Total focused time for the account (in seconds)
  def total_focused_time
    scoped_segments
      .sum("strftime('%s', stopped_at) - strftime('%s', started_at)")
  end

  # Focus time per day { "2026-02-05" => seconds, ... }
  def daily_focus
    scoped_segments
      .group(Arel.sql("strftime('%Y-%m-%d', started_at)"))
      .sum("strftime('%s', stopped_at) - strftime('%s', started_at)")
      .sort.to_h
  end

  # Context switches per day (number of segments started)
  def switches_per_day
    scoped_segments
      .group(Arel.sql("strftime('%Y-%m-%d', started_at)"))
      .count
      .sort.to_h
  end

  # Average segment length across all users (in seconds)
  def avg_segment_length
    scoped_segments
      .average("strftime('%s', stopped_at) - strftime('%s', started_at)")
      &.to_f || 0.0
  end

  private

  def scoped_segments
    scope = account.activity_segments.closed
    scope = scope.where("started_at >= ?", since) if since
    scope
  end
end
