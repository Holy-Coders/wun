class CreateActivitySegments < ActiveRecord::Migration[8.1]
  def change
    create_table :activity_segments do |t|
      t.references :account, null: false, foreign_key: true
      t.references :card, null: false, foreign_key: true
      t.references :user, null: false, foreign_key: true
      t.datetime :started_at, null: false
      t.datetime :stopped_at

      t.timestamps
    end

    add_index :activity_segments, [:user_id, :stopped_at]
    add_index :activity_segments, [:card_id, :started_at]
  end
end
