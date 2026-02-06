class SegmentAuditor
  attr_reader :account

  def initialize(account:)
    @account = account
  end

  # Run all checks, return a hash of issues
  def audit
    {
      overlapping: overlapping_segments,
      boundary_violations: boundary_crossing_segments,
      long_segments: long_segments,
      orphaned_open: orphaned_open_segments
    }
  end

  # Segments that overlap for the same user
  def overlapping_segments
    issues = []

    account.users.find_each do |user|
      segments = account.activity_segments.where(user: user).order(:started_at).to_a

      segments.each_cons(2) do |a, b|
        a_end = a.stopped_at || Time.current
        if b.started_at < a_end
          issues << { user_id: user.id, segment_a: a.id, segment_b: b.id,
                      overlap_seconds: (a_end - b.started_at).to_i }
        end
      end
    end

    issues
  end

  # Closed segments that span past the working-hours boundary
  def boundary_crossing_segments
    issues = []
    policy_cache = {}

    account.activity_segments.closed.includes(:user).find_each do |segment|
      membership = account.memberships.find_by(user: segment.user)
      next unless membership

      policy = policy_cache[membership.id] ||= WorkingHoursPolicy.new(membership.effective_working_hours)
      boundary = policy.boundary_time_for(segment.started_at)

      if segment.stopped_at > boundary + 1.minute # small grace
        issues << { segment_id: segment.id, user_id: segment.user_id,
                    boundary: boundary.iso8601, stopped_at: segment.stopped_at.iso8601 }
      end
    end

    issues
  end

  # Segments longer than a threshold (default 8 hours)
  def long_segments(max_hours: 8)
    threshold = max_hours.hours

    account.activity_segments.closed
      .where("strftime('%s', stopped_at) - strftime('%s', started_at) > ?", threshold.to_i)
      .map { |s| { segment_id: s.id, user_id: s.user_id, card_id: s.card_id,
                    hours: ((s.stopped_at - s.started_at) / 3600.0).round(2) } }
  end

  # Open segments with no corresponding sys:active card
  def orphaned_open_segments
    account.activity_segments.open.includes(:card).select do |segment|
      !segment.card.active?
    end.map { |s| { segment_id: s.id, user_id: s.user_id, card_id: s.card_id } }
  end

  # --- Repair methods ---

  # Close an open segment at a given time
  def close_segment(segment, at: Time.current)
    raise "Segment already closed" unless segment.open?
    segment.update!(stopped_at: [at, segment.started_at].max)
  end

  # Truncate a segment to end at a given time
  def truncate_segment(segment, at:)
    raise "Cannot truncate before start" if at < segment.started_at
    segment.update!(stopped_at: at)
  end

  # Split a segment at a given time into two segments
  def split_segment(segment, at:)
    raise "Split time must be within segment" unless at > segment.started_at
    raise "Split time must be before end" if segment.stopped_at && at >= segment.stopped_at

    original_end = segment.stopped_at
    segment.update!(stopped_at: at)

    account.activity_segments.create!(
      card: segment.card,
      user: segment.user,
      started_at: at,
      stopped_at: original_end
    )
  end

  # Auto-repair: close orphaned open segments
  def repair_orphaned!
    count = 0
    orphaned_open_segments.each do |issue|
      segment = account.activity_segments.find(issue[:segment_id])
      close_segment(segment)
      count += 1
    end
    count
  end

  # Auto-repair: truncate segments that cross working-hours boundary
  def repair_boundary_violations!
    count = 0
    policy_cache = {}

    boundary_crossing_segments.each do |issue|
      segment = account.activity_segments.find(issue[:segment_id])
      membership = account.memberships.find_by(user_id: segment.user_id)
      next unless membership

      policy = policy_cache[membership.id] ||= WorkingHoursPolicy.new(membership.effective_working_hours)
      boundary = policy.boundary_time_for(segment.started_at)

      truncate_segment(segment, at: boundary)
      count += 1
    end
    count
  end
end
