class TagsController < ApplicationController
  before_action :require_account
  before_action :require_authentication

  def index
    @tags = current_account.tags.user_tags.order(:name)
  end

  def show
    @tag = current_account.tags.find(params[:id])
    @cards = @tag.cards.where(account: current_account)
  end

  def create
    @tag = current_account.tags.create!(tag_params.merge(system: false))
    redirect_to tag_path(current_account.slug, @tag)
  rescue ActiveRecord::RecordInvalid
    redirect_to tags_path(current_account.slug), alert: "Tag already exists"
  end

  def destroy
    tag = current_account.tags.user_tags.find(params[:id])
    tag.destroy
    redirect_to tags_path(current_account.slug), notice: "Tag removed"
  end

  private

  def tag_params
    params.require(:tag).permit(:name)
  end
end
