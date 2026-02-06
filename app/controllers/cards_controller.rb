class CardsController < ApplicationController
  before_action :require_account
  before_action :require_authentication
  before_action :set_card, only: [:show, :edit, :update, :destroy, :activate, :deactivate, :done, :move, :idle_deactivate]

  def index
    @active_card = current_account.cards
      .joins(:tags).where(tags: { name: "sys:active" })
      .where(creator: current_user)
      .first

    @on_deck_cards = current_account.cards
      .where(creator: current_user)
      .where.not(id: @active_card&.id)
      .left_joins(:tags).where.not(tags: { name: "sys:done" })
      .distinct
      .order(position: :asc, updated_at: :desc)

    membership = current_account.memberships.find_by(user: current_user)
    if membership
      policy = WorkingHoursPolicy.new(membership.effective_working_hours)
      @outside_working_hours = !policy.working_time?
      @after_hours_active = membership.after_hours_override_active?
    end

    unless @active_card
      @pause_reason = determine_pause_reason
      @last_active_card = find_last_active_card
    end
  end

  def show
    segments = @card.activity_segments.closed
    @total_focus_seconds = segments.sum("strftime('%s', stopped_at) - strftime('%s', started_at)").to_i
    @focus_session_count = segments.count
    @backlinks = @card.mentions.includes(comment: [:creator, :card]).order(created_at: :desc)
    @suggested_tags = current_account.tags.user_tags.order(updated_at: :desc).limit(20).pluck(:name)
  end

  def new
    @card = current_account.cards.new
  end

  def create
    @card = rule_engine.create_card(
      title: card_params[:title],
      body: card_params[:body]
    )
    redirect_to card_path(current_account.slug, @card), notice: "Card created"
  rescue ActiveRecord::RecordInvalid
    @card = current_account.cards.new(card_params)
    render :new, status: :unprocessable_entity
  end

  def edit
  end

  def update
    if @card.update(card_params)
      redirect_to card_path(current_account.slug, @card), notice: "Card updated"
    else
      render :edit, status: :unprocessable_entity
    end
  end

  def destroy
    @card.destroy
    redirect_to account_root_path(current_account.slug), notice: "Card removed"
  end

  def activate
    rule_engine.activate(card: @card)
    redirect_to account_root_path(current_account.slug)
  end

  def deactivate
    rule_engine.deactivate(card: @card)
    redirect_to account_root_path(current_account.slug)
  end

  def done
    rule_engine.mark_done(card: @card)
    redirect_to account_root_path(current_account.slug)
  end

  def idle_deactivate
    idle_since = params[:idle_since]

    # Close the open segment at idle time instead of now
    if idle_since.present?
      open_segment = current_account.activity_segments.open.for_user(current_user).first
      if open_segment
        idle_time = Time.zone.parse(idle_since)
        open_segment.update!(stopped_at: idle_time) if idle_time > open_segment.started_at
      end
    end

    rule_engine.deactivate(card: @card)

    # Log the idle deactivation event
    current_account.events.create!(
      card: @card,
      actor: current_user,
      action: "idle_deactivated",
      metadata: { idle_since: idle_since }
    )

    redirect_to account_root_path(current_account.slug)
  end

  def working_late
    membership = current_account.memberships.find_by!(user: current_user)
    membership.update!(after_hours_until: 2.hours.from_now)

    current_account.events.create!(
      card: nil,
      actor: current_user,
      action: "working_hours_override",
      metadata: { until: membership.after_hours_until.iso8601 }
    )

    redirect_to account_root_path(current_account.slug), notice: "Working late — override active for 2 hours"
  end

  def move
    direction = params[:direction]
    sibling = current_account.cards.where(creator: current_user)

    if direction == "up"
      above = sibling.where("position < ?", @card.position).order(position: :desc).first
      if above
        above.position, @card.position = @card.position, above.position
        above.save!
        @card.save!
      end
    elsif direction == "down"
      below = sibling.where("position > ?", @card.position).order(position: :asc).first
      if below
        below.position, @card.position = @card.position, below.position
        below.save!
        @card.save!
      end
    end

    redirect_to account_root_path(current_account.slug)
  end

  private

  def set_card
    @card = current_account.cards.find(params[:id])
  end

  def card_params
    params.require(:card).permit(:title, :body)
  end

  def rule_engine
    @rule_engine ||= RuleEngine.new(account: current_account, actor: current_user)
  end

  def determine_pause_reason
    recent_event = current_account.events
      .where(actor: current_user)
      .where(action: %w[working_hours_auto_pause idle_deactivated tag_removed])
      .order(created_at: :desc)
      .first

    return :just_started unless recent_event

    case recent_event.action
    when "working_hours_auto_pause"
      :working_hours
    when "idle_deactivated"
      :idle
    when "tag_removed"
      if recent_event.metadata&.dig("tag") == "sys:active"
        :manual
      else
        :just_started
      end
    else
      :just_started
    end
  end

  def find_last_active_card
    last_segment = current_account.activity_segments
      .closed
      .for_user(current_user)
      .order(stopped_at: :desc)
      .first

    return nil unless last_segment

    card = last_segment.card
    # Don't suggest resuming a done card
    card.done? ? nil : card
  end
end
