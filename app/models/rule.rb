class Rule < ApplicationRecord
  belongs_to :account

  TRIGGERS = %w[
    tag_added
    tag_removed
    comment_added
    card_created
  ].freeze

  validates :name, presence: true
  validates :trigger, presence: true, inclusion: { in: TRIGGERS }
  validates :action_type, presence: true

  scope :active, -> { where(active: true) }
  scope :for_trigger, ->(trigger) { active.where(trigger: trigger).order(:position, :id) }
  scope :system_rules, -> { where(system: true) }
  scope :user_rules, -> { where(system: false) }
end
