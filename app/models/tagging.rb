class Tagging < ApplicationRecord
  belongs_to :card
  belongs_to :tag
  belongs_to :creator, class_name: "User"

  validates :tag_id, uniqueness: { scope: :card_id }
end
