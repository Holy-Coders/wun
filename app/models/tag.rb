class Tag < ApplicationRecord
  belongs_to :account

  has_many :taggings, dependent: :destroy
  has_many :cards, through: :taggings

  validates :name, presence: true, uniqueness: { scope: :account_id }

  scope :system_tags, -> { where(system: true) }
  scope :user_tags, -> { where(system: false) }

  def system_tag?
    system?
  end
end
