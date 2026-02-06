class Account < ApplicationRecord
  has_many :users, dependent: :destroy
  has_many :memberships, dependent: :destroy
  has_many :members, through: :memberships, source: :user
  has_many :cards, dependent: :destroy
  has_many :tags, dependent: :destroy
  has_many :events, dependent: :destroy
  has_many :rules, dependent: :destroy
  has_many :activity_segments, dependent: :destroy

  validates :name, presence: true
  validates :slug, presence: true, uniqueness: true

  DEFAULT_WORKING_HOURS = {
    "timezone" => "Asia/Jerusalem",
    "days" => [0, 1, 2, 3, 4], # Sun-Thu
    "start_hour" => 9,
    "end_hour" => 18
  }.freeze

  def effective_working_hours
    working_hours.presence || DEFAULT_WORKING_HOURS
  end

  normalizes :slug, with: ->(slug) { slug.strip.downcase.gsub(/[^a-z0-9-]/, "-").gsub(/-+/, "-") }
end
