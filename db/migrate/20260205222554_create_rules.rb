class CreateRules < ActiveRecord::Migration[8.1]
  def change
    create_table :rules do |t|
      t.references :account, null: false, foreign_key: true
      t.string :name, null: false
      t.string :trigger, null: false
      t.string :action_type, null: false
      t.json :action_config
      t.boolean :active, default: true, null: false

      t.timestamps
    end

    add_index :rules, [:account_id, :trigger]
  end
end
