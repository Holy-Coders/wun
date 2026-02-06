class CreateEvents < ActiveRecord::Migration[8.1]
  def change
    create_table :events do |t|
      t.references :account, null: false, foreign_key: true
      t.references :card, null: false, foreign_key: true
      t.references :actor, null: false, foreign_key: { to_table: :users }
      t.string :action, null: false
      t.json :metadata

      t.timestamps
    end

    add_index :events, [:account_id, :action]
    add_index :events, [:card_id, :created_at]
  end
end
