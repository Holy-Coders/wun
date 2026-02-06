class TaggingsController < ApplicationController
  before_action :require_account
  before_action :require_authentication
  before_action :set_card

  def create
    tag_name = tagging_params[:tag_name].to_s.strip

    if tag_name.start_with?("sys:")
      redirect_to card_path(current_account.slug, @card), alert: "System tags cannot be added manually"
      return
    end

    rule_engine.add_tag(card: @card, tag_name: tag_name)
    redirect_to card_path(current_account.slug, @card)
  end

  def destroy
    tagging = @card.taggings.find(params[:id])

    if tagging.tag.system?
      redirect_to card_path(current_account.slug, @card), alert: "System tags cannot be removed manually"
      return
    end

    rule_engine.remove_tag(card: @card, tag_name: tagging.tag.name)
    redirect_to card_path(current_account.slug, @card)
  end

  private

  def set_card
    @card = current_account.cards.find(params[:card_id])
  end

  def tagging_params
    params.require(:tagging).permit(:tag_name)
  end

  def rule_engine
    @rule_engine ||= RuleEngine.new(account: current_account, actor: current_user)
  end
end
