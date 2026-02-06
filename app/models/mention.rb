class Mention < ApplicationRecord
  belongs_to :comment
  belongs_to :card
end
