class CreateTaggings < ActiveRecord::Migration[8.1]
  def change
    create_table :taggings do |t|
      t.references :card, null: false, foreign_key: true
      t.references :tag, null: false, foreign_key: true
      t.references :creator, null: false, foreign_key: { to_table: :users }

      t.timestamps
    end

    add_index :taggings, [:card_id, :tag_id], unique: true
  end
end
