class RulesController < ApplicationController
  before_action :require_account
  before_action :require_authentication
  before_action :require_manager
  before_action :set_rule, only: [:edit, :update, :destroy]

  def index
    @system_rules = current_account.rules.system_rules.order(:position, :id)
    @user_rules = current_account.rules.user_rules.order(:position, :id)
  end

  def new
    @rule = current_account.rules.new(active: true, position: next_position)
  end

  def create
    @rule = current_account.rules.new(rule_params)
    @rule.system = false

    if @rule.save
      redirect_to rules_path(current_account.slug), notice: "Rule created"
    else
      render :new, status: :unprocessable_entity
    end
  end

  def edit
    if @rule.system?
      redirect_to rules_path(current_account.slug), alert: "System rules cannot be edited"
    end
  end

  def update
    if @rule.system?
      redirect_to rules_path(current_account.slug), alert: "System rules cannot be edited"
      return
    end

    if @rule.update(rule_params)
      redirect_to rules_path(current_account.slug), notice: "Rule updated"
    else
      render :edit, status: :unprocessable_entity
    end
  end

  def destroy
    if @rule.system?
      redirect_to rules_path(current_account.slug), alert: "System rules cannot be deleted"
      return
    end

    @rule.destroy
    redirect_to rules_path(current_account.slug), notice: "Rule deleted"
  end

  private

  def set_rule
    @rule = current_account.rules.find(params[:id])
  end

  def rule_params
    params.require(:rule).permit(:name, :trigger, :action_type, :active, :position,
      action_config: [:when_tag, :tag, :keyword])
  end

  def next_position
    (current_account.rules.maximum(:position) || 0) + 1
  end
end
