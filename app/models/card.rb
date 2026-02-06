class Card < ApplicationRecord
  belongs_to :account
  belongs_to :creator, class_name: "User"

  has_rich_text :body

  has_many :taggings, dependent: :destroy
  has_many :tags, through: :taggings
  has_many :comments, dependent: :destroy
  has_many :events, dependent: :destroy
  has_many :activity_segments, dependent: :destroy
  has_many :mentions, dependent: :destroy
  has_many :mentioning_comments, through: :mentions, source: :comment

  validates :title, presence: true

  def active?
    tags.exists?(name: "sys:active")
  end

  def done?
    tags.exists?(name: "sys:done")
  end

  # Generic: does this card have any tag starting with prefix?
  def has_tag_prefix?(prefix)
    tags.where("name LIKE ?", "#{prefix}:%").exists?
  end

  # Generic: tag names matching a prefix
  def tags_with_prefix(prefix)
    tags.where("name LIKE ?", "#{prefix}:%").pluck(:name)
  end

  # Generic: extract card IDs from prefix:card:N tags
  def card_refs_for(prefix)
    tags_with_prefix(prefix)
      .map { |name| StructuredTag.new(name) }
      .select(&:card_ref?)
      .map(&:card_ref_id)
  end

  def blocked?
    has_tag_prefix?("blocked")
  end

  def blocking_card_ids
    card_refs_for("blocked")
  end
end
