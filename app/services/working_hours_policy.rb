class WorkingHoursPolicy
  attr_reader :config

  def initialize(config)
    @config = config
  end

  def timezone
    ActiveSupport::TimeZone[config["timezone"] || "UTC"]
  end

  def working_days
    config["days"] || [0, 1, 2, 3, 4] # Sun-Thu default
  end

  def start_hour
    config["start_hour"] || 9
  end

  def end_hour
    config["end_hour"] || 18
  end

  # Is the given time within working hours?
  def working_time?(time = Time.current)
    local = time.in_time_zone(timezone)
    working_day?(local) && working_hour?(local)
  end

  # Is the given time on a working day?
  def working_day?(time = Time.current)
    local = time.in_time_zone(timezone)
    working_days.include?(local.wday)
  end

  # Is the given time within working hours on that day?
  def working_hour?(time = Time.current)
    local = time.in_time_zone(timezone)
    local.hour >= start_hour && local.hour < end_hour
  end

  # The next end-of-day boundary from the given time
  def next_boundary(from = Time.current)
    local = from.in_time_zone(timezone)

    if working_time?(from) || (working_day?(from) && local.hour < end_hour)
      # Boundary is today at end_hour
      local.change(hour: end_hour, min: 0, sec: 0)
    else
      # Find the next working day
      next_day = next_working_day(local)
      next_day.change(hour: end_hour, min: 0, sec: 0)
    end
  end

  # The boundary time for today (or a given date) — the end of working hours
  def boundary_time_for(date = Time.current)
    local = date.in_time_zone(timezone)
    local.change(hour: end_hour, min: 0, sec: 0)
  end

  # The start time for today (or a given date)
  def start_time_for(date = Time.current)
    local = date.in_time_zone(timezone)
    local.change(hour: start_hour, min: 0, sec: 0)
  end

  private

  def next_working_day(from)
    date = from + 1.day
    7.times do
      return date if working_days.include?(date.wday)
      date += 1.day
    end
    date # fallback
  end
end
