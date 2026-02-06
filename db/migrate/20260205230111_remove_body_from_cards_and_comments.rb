class RemoveBodyFromCardsAndComments < ActiveRecord::Migration[8.1]
  def change
    remove_column :cards, :body, :text
    remove_column :comments, :body, :text, null: false
  end
end
