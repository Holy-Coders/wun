# This file is auto-generated from the current state of the database. Instead
# of editing this file, please use the migrations feature of Active Record to
# incrementally modify your database, and then regenerate this schema definition.
#
# This file is the source Rails uses to define your schema when running `bin/rails
# db:schema:load`. When creating a new database, `bin/rails db:schema:load` tends to
# be faster and is potentially less error prone than running all of your
# migrations from scratch. Old migrations may fail to apply correctly if those
# migrations use external dependencies or application code.
#
# It's strongly recommended that you check this file into your version control system.

ActiveRecord::Schema[8.1].define(version: 2026_02_05_233309) do
  create_table "accounts", force: :cascade do |t|
    t.datetime "created_at", null: false
    t.string "name", null: false
    t.string "slug", null: false
    t.datetime "updated_at", null: false
    t.json "working_hours"
    t.index ["slug"], name: "index_accounts_on_slug", unique: true
  end

  create_table "action_text_rich_texts", force: :cascade do |t|
    t.text "body"
    t.datetime "created_at", null: false
    t.string "name", null: false
    t.bigint "record_id", null: false
    t.string "record_type", null: false
    t.datetime "updated_at", null: false
    t.index ["record_type", "record_id", "name"], name: "index_action_text_rich_texts_uniqueness", unique: true
  end

  create_table "active_storage_attachments", force: :cascade do |t|
    t.bigint "blob_id", null: false
    t.datetime "created_at", null: false
    t.string "name", null: false
    t.bigint "record_id", null: false
    t.string "record_type", null: false
    t.index ["blob_id"], name: "index_active_storage_attachments_on_blob_id"
    t.index ["record_type", "record_id", "name", "blob_id"], name: "index_active_storage_attachments_uniqueness", unique: true
  end

  create_table "active_storage_blobs", force: :cascade do |t|
    t.bigint "byte_size", null: false
    t.string "checksum"
    t.string "content_type"
    t.datetime "created_at", null: false
    t.string "filename", null: false
    t.string "key", null: false
    t.text "metadata"
    t.string "service_name", null: false
    t.index ["key"], name: "index_active_storage_blobs_on_key", unique: true
  end

  create_table "active_storage_variant_records", force: :cascade do |t|
    t.bigint "blob_id", null: false
    t.string "variation_digest", null: false
    t.index ["blob_id", "variation_digest"], name: "index_active_storage_variant_records_uniqueness", unique: true
  end

  create_table "activity_segments", force: :cascade do |t|
    t.integer "account_id", null: false
    t.integer "card_id", null: false
    t.datetime "created_at", null: false
    t.datetime "started_at", null: false
    t.datetime "stopped_at"
    t.datetime "updated_at", null: false
    t.integer "user_id", null: false
    t.index ["account_id"], name: "index_activity_segments_on_account_id"
    t.index ["card_id", "started_at"], name: "index_activity_segments_on_card_id_and_started_at"
    t.index ["card_id"], name: "index_activity_segments_on_card_id"
    t.index ["user_id", "stopped_at"], name: "index_activity_segments_on_user_id_and_stopped_at"
    t.index ["user_id"], name: "index_activity_segments_on_user_id"
  end

  create_table "cards", force: :cascade do |t|
    t.integer "account_id", null: false
    t.datetime "created_at", null: false
    t.integer "creator_id", null: false
    t.integer "position", default: 0, null: false
    t.string "title", null: false
    t.datetime "updated_at", null: false
    t.index ["account_id"], name: "index_cards_on_account_id"
    t.index ["creator_id"], name: "index_cards_on_creator_id"
  end

  create_table "comments", force: :cascade do |t|
    t.integer "card_id", null: false
    t.datetime "created_at", null: false
    t.integer "creator_id", null: false
    t.datetime "updated_at", null: false
    t.index ["card_id"], name: "index_comments_on_card_id"
    t.index ["creator_id"], name: "index_comments_on_creator_id"
  end

  create_table "events", force: :cascade do |t|
    t.integer "account_id", null: false
    t.string "action", null: false
    t.integer "actor_id", null: false
    t.integer "card_id"
    t.datetime "created_at", null: false
    t.json "metadata"
    t.datetime "updated_at", null: false
    t.index ["account_id", "action"], name: "index_events_on_account_id_and_action"
    t.index ["account_id"], name: "index_events_on_account_id"
    t.index ["actor_id"], name: "index_events_on_actor_id"
    t.index ["card_id", "created_at"], name: "index_events_on_card_id_and_created_at"
    t.index ["card_id"], name: "index_events_on_card_id"
  end

  create_table "memberships", force: :cascade do |t|
    t.integer "account_id", null: false
    t.datetime "after_hours_until"
    t.datetime "created_at", null: false
    t.string "role", default: "member", null: false
    t.datetime "updated_at", null: false
    t.integer "user_id", null: false
    t.json "working_hours_override"
    t.index ["account_id", "user_id"], name: "index_memberships_on_account_id_and_user_id", unique: true
    t.index ["account_id"], name: "index_memberships_on_account_id"
    t.index ["user_id"], name: "index_memberships_on_user_id"
  end

  create_table "mentions", force: :cascade do |t|
    t.integer "card_id", null: false
    t.integer "comment_id", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.index ["card_id"], name: "index_mentions_on_card_id"
    t.index ["comment_id", "card_id"], name: "index_mentions_on_comment_id_and_card_id", unique: true
    t.index ["comment_id"], name: "index_mentions_on_comment_id"
  end

  create_table "rules", force: :cascade do |t|
    t.integer "account_id", null: false
    t.json "action_config"
    t.string "action_type", null: false
    t.boolean "active", default: true, null: false
    t.datetime "created_at", null: false
    t.string "name", null: false
    t.integer "position", default: 0, null: false
    t.boolean "system", default: false, null: false
    t.string "trigger", null: false
    t.datetime "updated_at", null: false
    t.index ["account_id", "trigger"], name: "index_rules_on_account_id_and_trigger"
    t.index ["account_id"], name: "index_rules_on_account_id"
  end

  create_table "taggings", force: :cascade do |t|
    t.integer "card_id", null: false
    t.datetime "created_at", null: false
    t.integer "creator_id", null: false
    t.integer "tag_id", null: false
    t.datetime "updated_at", null: false
    t.index ["card_id", "tag_id"], name: "index_taggings_on_card_id_and_tag_id", unique: true
    t.index ["card_id"], name: "index_taggings_on_card_id"
    t.index ["creator_id"], name: "index_taggings_on_creator_id"
    t.index ["tag_id"], name: "index_taggings_on_tag_id"
  end

  create_table "tags", force: :cascade do |t|
    t.integer "account_id", null: false
    t.datetime "created_at", null: false
    t.string "name", null: false
    t.boolean "system", default: false, null: false
    t.datetime "updated_at", null: false
    t.index ["account_id", "name"], name: "index_tags_on_account_id_and_name", unique: true
    t.index ["account_id"], name: "index_tags_on_account_id"
  end

  create_table "users", force: :cascade do |t|
    t.integer "account_id", null: false
    t.datetime "created_at", null: false
    t.string "email_address", null: false
    t.string "name", null: false
    t.string "password_digest", null: false
    t.datetime "updated_at", null: false
    t.index ["account_id", "email_address"], name: "index_users_on_account_id_and_email_address", unique: true
    t.index ["account_id"], name: "index_users_on_account_id"
  end

  add_foreign_key "active_storage_attachments", "active_storage_blobs", column: "blob_id"
  add_foreign_key "active_storage_variant_records", "active_storage_blobs", column: "blob_id"
  add_foreign_key "activity_segments", "accounts"
  add_foreign_key "activity_segments", "cards"
  add_foreign_key "activity_segments", "users"
  add_foreign_key "cards", "accounts"
  add_foreign_key "cards", "users", column: "creator_id"
  add_foreign_key "comments", "cards"
  add_foreign_key "comments", "users", column: "creator_id"
  add_foreign_key "events", "accounts"
  add_foreign_key "events", "cards", on_delete: :nullify
  add_foreign_key "events", "users", column: "actor_id"
  add_foreign_key "memberships", "accounts"
  add_foreign_key "memberships", "users"
  add_foreign_key "mentions", "cards"
  add_foreign_key "mentions", "comments"
  add_foreign_key "rules", "accounts"
  add_foreign_key "taggings", "cards"
  add_foreign_key "taggings", "tags"
  add_foreign_key "taggings", "users", column: "creator_id"
  add_foreign_key "tags", "accounts"
  add_foreign_key "users", "accounts"
end
