class CommentsController < ApplicationController
  before_action :require_account
  before_action :require_authentication
  before_action :set_card

  def create
    rule_engine.add_comment(card: @card, body: comment_params[:body])
    redirect_to card_path(current_account.slug, @card), notice: "Comment added"
  rescue ActiveRecord::RecordInvalid
    redirect_to card_path(current_account.slug, @card), alert: "Comment cannot be blank"
  end

  def destroy
    comment = @card.comments.find(params[:id])
    comment.destroy
    redirect_to card_path(current_account.slug, @card), notice: "Comment removed"
  end

  private

  def set_card
    @card = current_account.cards.find(params[:card_id])
  end

  def comment_params
    params.require(:comment).permit(:body)
  end

  def rule_engine
    @rule_engine ||= RuleEngine.new(account: current_account, actor: current_user)
  end
end
