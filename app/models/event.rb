class Event < ApplicationRecord
  belongs_to :account
  belongs_to :card, optional: true
  belongs_to :actor, class_name: "User"

  validates :action, presence: true

  ACTIONS = %w[
    tag_added
    tag_removed
    comment_added
    card_created
    rule_executed
    idle_deactivated
    working_hours_auto_pause
    working_hours_override
  ].freeze

  validates :action, inclusion: { in: ACTIONS }
end
