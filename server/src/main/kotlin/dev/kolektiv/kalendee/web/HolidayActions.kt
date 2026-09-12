package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCustomHoliday
import dev.kolektiv.keel.KeelAction

class HolidayActions(
    private val store: CalendarStore,
    private val auth: AuthService,
    private val settings: AuthSettings,
) {
    @KeelAction("kalendee.setShowHolidays")
    suspend fun setShow(input: SetShowHolidaysIn): HolidayStateOut = mapDomainErrors("showHolidays") {
        val user = requireSessionUser(auth, settings)
        store.setShowHolidays(user.id, input.showHolidays).toState()
    }

    @KeelAction("kalendee.updateHolidaySubscriptions")
    suspend fun updateSubscriptions(input: UpdateHolidaySubscriptionsIn): HolidayStateOut =
        mapDomainErrors("subscribedIds") {
            val user = requireSessionUser(auth, settings)
            store.setHolidaySubscriptions(user.id, input.subscribedIds).toState()
        }

    @KeelAction("kalendee.createCustomHoliday")
    suspend fun createCustom(input: CreateCustomHolidayIn): CustomHolidaySummary = mapDomainErrors("title") {
        val user = requireSessionUser(auth, settings)
        store.createCustomHoliday(
            user.id,
            CreateCustomHoliday(title = input.title, month = input.month, day = input.day),
        ).toSummary()
    }

    @KeelAction("kalendee.deleteCustomHoliday")
    suspend fun deleteCustom(input: DeleteCustomHolidayIn): DeletedOut = mapDomainErrors("id") {
        val user = requireSessionUser(auth, settings)
        if (!store.deleteCustomHoliday(input.id, user.id)) {
            throw CalendarException.NotFound("holiday not found")
        }
        DeletedOut()
    }
}
