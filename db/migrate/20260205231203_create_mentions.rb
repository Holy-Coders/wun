class CreateMentions < ActiveRecord::Migration[8.1]
  def change
    create_table :mentions do |t|
      t.references :comment, null: false, foreign_key: true
      t.references :card, null: false, foreign_key: true

      t.timestamps
    end
    add_index :mentions, [:comment_id, :card_id], unique: true
  end
end
