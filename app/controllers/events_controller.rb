class EventsController < ApplicationController
  before_action :require_account
  before_action :require_authentication

  def index
    @card = current_account.cards.find(params[:card_id])
    @events = @card.events.order(created_at: :desc).includes(:actor)
  end
end
