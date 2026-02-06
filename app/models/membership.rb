class Membership < ApplicationRecord
  belongs_to :account
  belongs_to :user

  validates :user_id, uniqueness: { scope: :account_id }
  validates :role, presence: true

  ROLES = %w[member manager admin].freeze
  validates :role, inclusion: { in: ROLES }

  def admin?
    role == "admin"
  end

  def manager?
    role == "manager" || admin?
  end

  def after_hours_override_active?
    after_hours_until.present? && after_hours_until > Time.current
  end

  def effective_working_hours
    working_hours_override.presence || account.effective_working_hours
  end
end
