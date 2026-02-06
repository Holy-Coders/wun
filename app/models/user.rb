class User < ApplicationRecord
  belongs_to :account

  has_secure_password

  has_many :memberships, dependent: :destroy
  has_many :accounts, through: :memberships
  has_many :cards, foreign_key: :creator_id, dependent: :destroy
  has_many :taggings, foreign_key: :creator_id, dependent: :destroy
  has_many :comments, foreign_key: :creator_id, dependent: :destroy
  has_many :events, foreign_key: :actor_id, dependent: :destroy
  has_many :activity_segments, dependent: :destroy

  validates :name, presence: true
  validates :email_address, presence: true, uniqueness: { scope: :account_id }

  normalizes :email_address, with: ->(email) { email.strip.downcase }

  def member_of?(account)
    memberships.exists?(account: account)
  end

  def membership_for(account)
    memberships.find_by(account: account)
  end

  def manager_of?(account)
    membership_for(account)&.manager? || false
  end

  def admin_of?(account)
    membership_for(account)&.admin? || false
  end
end
