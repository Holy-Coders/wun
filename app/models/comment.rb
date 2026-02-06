class Comment < ApplicationRecord
  belongs_to :card
  belongs_to :creator, class_name: "User"

  has_rich_text :body
  has_many :mentions, dependent: :destroy
end
