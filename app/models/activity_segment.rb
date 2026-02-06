class ActivitySegment < ApplicationRecord
  belongs_to :account
  belongs_to :card
  belongs_to :user

  validates :started_at, presence: true

  scope :open, -> { where(stopped_at: nil) }
  scope :closed, -> { where.not(stopped_at: nil) }
  scope :for_user, ->(user) { where(user: user) }

  def open?
    stopped_at.nil?
  end

  def duration
    return nil if open?
    stopped_at - started_at
  end
end
